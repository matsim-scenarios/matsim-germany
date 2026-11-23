package org.matsim.plane;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.referencing.GeodeticCalculator;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.TransitRouteStopImpl;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.utils.objectattributes.attributable.Attributable;
import org.matsim.utils.objectattributes.attributable.Attributes;
import picocli.CommandLine;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Parses EUROCONTROL ADRR flight data and generates a MATSim transit schedule, network, and
 * vehicle file. Also generates a plane network containing airports and connection links.
 *
 * <p>See the package documentation for details on the input dataset, geographic filtering,
 * aircraft types, operators, and empirically estimated vehicle speeds.</p>
 */
public class CreatePlaneSchedule implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreatePlaneSchedule.class);
	private static final Set<String> AIRPLANE_ALLOWED_MODES = Set.of(TransportMode.airplane);

	@CommandLine.Option(names = "--adrr-flight-data", description = "Path to the flights file of the EUROCONTROL ADRR dataset", required = true)
	private String flightPath;

	@CommandLine.Option(names = "--sample-day", description = "The day to sample from the dataset. Make sure to give a date with no holidays or other unusual events. Format: DD-MM-YYYY", defaultValue = "06-12-2023")
	private String sampleDay;

	@CommandLine.Option(names = "--default-passenger-capacity", description = "Passenger capacity assigned to every aircraft type", defaultValue = "160")
	private int defaultPassengerCapacity;

	@CommandLine.Option(names = "--boarding-time", description = "Negative offset of departure in seconds, simulating boarding time. " +
		"Agents need to arrive at least <boarding-time> seconds before actual departure to enter the flight. Arrival is not changed by this parameter", defaultValue = "1800")
	private int boarding_time;

	@CommandLine.Option(names ="--output-dir", description = "Path to output directory", required = true)
	private Path outputDir;

	@CommandLine.Option(names = "--departure-area", description = "Optional polygon file (.shp, .gpkg, or .zip). Only flights departing inside this area are included.")
	private String departureArea;

	@CommandLine.Option(names = "--destination-area", description = "Optional polygon file (.shp, .gpkg, or .zip). Only flights arriving inside this area are included.")
	private String destinationArea;

	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions("EPSG:4326");

	public static void main(String[] args) {
		new CreatePlaneSchedule().execute(args);
	}

	@Override
	public Integer call() {
		// Read in the Flights CSV
		Set<Tuple<String, Coord>> airportSet = new HashSet<>();
		List<FlightInformation> flightInformationList = new LinkedList<>();
		if (crs.getTargetCRS() == null) {
			throw new IllegalArgumentException("Target CRS must be specified. Please use the ----target-crs option");
		}
		CoordinateTransformation transformation = crs.getTransformation();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
		DateTimeFormatter sampleDayFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
		LocalDate selectedDay = LocalDate.parse(sampleDay, sampleDayFormatter);
		ShpOptions.Index departureAreaIndex = createAreaIndex(departureArea);
		ShpOptions.Index destinationAreaIndex = createAreaIndex(destinationArea);

		var format = CSVFormat.DEFAULT.builder()
			.setDelimiter(",")
			.setSkipHeaderRecord(true)
			.setHeader()
			.build();

		try (var reader = Files.newBufferedReader(Path.of(flightPath)); var parser = CSVParser.parse(reader, format)) {
			for(var record : parser){
				LocalDateTime filedOffBlockTime = LocalDateTime.parse(record.get("FILED OFF BLOCK TIME"), formatter);
				if (!filedOffBlockTime.toLocalDate().equals(selectedDay)) {
					continue;
				}

				String lonADEP = record.get("ADEP Longitude");
				String latADEP = record.get("ADEP Latitude");
				String lonADES = record.get("ADES Longitude");
				String latADES = record.get("ADES Latitude");
				Coord adepCoord = parseCoordinate(lonADEP, latADEP);
				Coord adesCoord = parseCoordinate(lonADES, latADES);

				if (isInsideArea(departureAreaIndex, adepCoord) || isInsideArea(destinationAreaIndex, adesCoord)) {
					continue;
				}

				flightInformationList.add( new FlightInformation(
					record.get("ECTRL ID"),
					record.get("ADEP"),
					record.get("ADES"),
					filedOffBlockTime,
					LocalDateTime.parse(record.get("FILED ARRIVAL TIME"), formatter),
					Double.parseDouble(record.get("Actual Distance Flown (nm)"))*1852,
					normalizeCode(record.get("AC Type"), "UNKNOWN"),
					normalizeCode(record.get("AC Operator"), "ZZZ")
				));

				if(adepCoord != null){
					airportSet.add(new Tuple<>(record.get("ADEP"), adepCoord));
				} else {
					log.warn("Skipped ADEP airport {} due to missing coordinates!", record.get("ADEP"));
				}

				if(adesCoord != null) {
					airportSet.add(new Tuple<>(record.get("ADES"), adesCoord));
				} else {
					log.warn("Skipped ADES airport {} due to missing coordinates!", record.get("ADES"));
				}
			}
		} catch(IOException e){
			throw new RuntimeException(e);
		}
		log.info("Selected {} flights and {} airports for {}", flightInformationList.size(), airportSet.size(), selectedDay);

		// Prepare Scenario
		Scenario planeScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitScheduleFactoryImpl scheduleFactory = new TransitScheduleFactoryImpl();

		Map<String, Double> maximumObservedVelocityByAircraftType = new HashMap<>();
		for (FlightInformation flight : flightInformationList) {
			double travelTime = ChronoUnit.SECONDS.between(flight.filedOffBlockTime, flight.filedArrivalTime);
			if (travelTime > 0. && flight.distance > 0.) {
				maximumObservedVelocityByAircraftType.merge(
					flight.aircraftType,
					flight.distance / travelTime,
					Math::max
				);
			}
		}

		Map<String, VehicleType> planeTypes = new HashMap<>();
		Set<String> aircraftTypes = new TreeSet<>();
		flightInformationList.forEach(flight -> aircraftTypes.add(flight.aircraftType));
		for (String aircraftType : aircraftTypes) {
			VehicleType planeType = createPlaneType(aircraftType, maximumObservedVelocityByAircraftType.get(aircraftType));
			planeScenario.getTransitVehicles().addVehicleType(planeType);
			planeTypes.put(aircraftType, planeType);
		}

		// Create the plane-network and add airports into it (without connections)
		for(var airport : airportSet){
			addAirportToNetwork(planeScenario.getNetwork(), airport, transformation);
			TransitStopFacility stopFacility = scheduleFactory.createTransitStopFacility(
				Id.create(airport.getFirst(), TransitStopFacility.class),
				planeScenario.getNetwork().getNodes().get(Id.createNodeId(airport.getFirst())).getCoord(),
				false
			);
			stopFacility.setLinkId(Id.createLinkId(airport.getFirst()));
			stopFacility.getAttributes().putAttribute("type", "airport");
			planeScenario.getTransitSchedule().addStopFacility(stopFacility);
		}

		Map<String, DoubleSummaryStatistics> distanceStatisticsByConnection = new HashMap<>();
		Map<String, Double> minimumTravelTimeByConnection = new HashMap<>();
		for (var flightInformation : flightInformationList) {
			double travelTime = ChronoUnit.SECONDS.between(flightInformation.filedOffBlockTime, flightInformation.filedArrivalTime);
			if (travelTime < 1. || flightInformation.distance < 1.) {
				continue;
			}

			String connectionId = flightInformation.ADEP + "_" + flightInformation.ADES;
			distanceStatisticsByConnection.computeIfAbsent(connectionId, ignored -> new DoubleSummaryStatistics()).accept(flightInformation.distance);
			minimumTravelTimeByConnection.merge(connectionId, travelTime, Math::min);
		}

		// Create schedule with given flightInformation
		for(var flightInformation : flightInformationList){
			// Prepare Ids
			String connectionId = flightInformation.ADEP + "_" + flightInformation.ADES;
			String flightId = connectionId + "_" + flightInformation.id;
			Id<Link> linkId = Id.createLinkId(connectionId);
			Id<TransitLine> lineId = Id.create(flightId, TransitLine.class);
			Id<TransitRoute> routeId = Id.create(flightId, TransitRoute.class);
			Id<Vehicle> vehicleId = Id.create(flightId, Vehicle.class);
			Id<Departure> departureId = Id.create(flightId, Departure.class);

			// Skip if flightInformation can't be added
			double time = ChronoUnit.SECONDS.between(flightInformation.filedOffBlockTime, flightInformation.filedArrivalTime);

			if(time < 1.){
				log.warn("Skipped flight (id={}) due to too short flight time (time={})", flightInformation.id, time);
				continue;
			}

			if(flightInformation.distance < 1.){
				log.warn("Skipped flight (id={}) due to too short flight distance (distance={})", flightInformation.id, flightInformation.distance);
				continue;
			}

			Node from = planeScenario.getNetwork().getNodes().get(Id.createNodeId(flightInformation.ADEP + "runwayOutbound"));
			Node to = planeScenario.getNetwork().getNodes().get(Id.createNodeId(flightInformation.ADES + "runwayInbound"));

			if(from == null){
				log.warn("Skipped flight (id={}) due to missing departure airport ({})", flightInformation.id, flightInformation.ADEP);
				continue;
			}

			if(to == null){
				log.warn("Skipped flight (id={}) due to missing arrival airport ({})", flightInformation.id, flightInformation.ADES);
				continue;
			}

			if (!planeScenario.getNetwork().getLinks().containsKey(linkId)) {
				double averageDistance = distanceStatisticsByConnection.get(connectionId).getAverage();
				double minimumTravelTime = minimumTravelTimeByConnection.get(connectionId);
				Link flightLink = NetworkUtils.createLink(
					linkId,
					from,
					to,
					planeScenario.getNetwork(),
					averageDistance,
					averageDistance / minimumTravelTime,
					3600,
					1
				);
				flightLink.setAllowedModes(AIRPLANE_ALLOWED_MODES);
				flightLink.getAttributes().putAttribute("flightConnection", "yes");
				planeScenario.getNetwork().addLink(flightLink);
			}

			// Add the transitLine

			TransitLine transitLine = scheduleFactory.createTransitLine(lineId);
			addFlightAttributes(transitLine, flightInformation);
			planeScenario.getTransitSchedule().addTransitLine(transitLine);

			List<Id<Link>> links = List.of(
				Id.createLinkId(flightInformation.ADEP + "taxiOutbound"),
				Id.createLinkId(flightInformation.ADEP + "runwayOutbound"),
				linkId,
				Id.createLinkId(flightInformation.ADES + "runwayInbound"),
				Id.createLinkId(flightInformation.ADES + "taxiInbound")
			);

			NetworkRoute route = RouteUtils.createLinkNetworkRouteImpl(
				Id.createLinkId(flightInformation.ADEP),
				links,
				Id.createLinkId(flightInformation.ADES)
			);

			List<TransitRouteStop> stops = List.of(
				(new TransitRouteStopImpl.Builder())
					.stop(planeScenario.getTransitSchedule().getFacilities().get(Id.create(flightInformation.ADEP, TransitStopFacility.class)))
					.departureOffset(0)
					.build(),
				(new TransitRouteStopImpl.Builder())
					.stop(planeScenario.getTransitSchedule().getFacilities().get(Id.create(flightInformation.ADES, TransitStopFacility.class)))
					.arrivalOffset(time + boarding_time)
					.departureOffset(time + boarding_time)
					.awaitDepartureTime(true)
					.build()
			);

			transitLine.addRoute(scheduleFactory.createTransitRoute(routeId, route, stops, TransportMode.airplane));

			Vehicle plane = VehicleUtils.createVehicle(vehicleId, planeTypes.get(flightInformation.aircraftType));
			plane.getAttributes().putAttribute("airplane", "yes");
			addFlightAttributes(plane, flightInformation);
			planeScenario.getTransitVehicles().addVehicle(plane);
			Departure departure = scheduleFactory.createDeparture(departureId, flightInformation.filedOffBlockTime.toLocalTime().toSecondOfDay() - boarding_time);
			departure.setVehicleId(vehicleId);
			transitLine.getRoutes().get(routeId).addDeparture(departure);

			// Add attributes to the schedules
			transitLine.getAttributes().putAttribute("flightConnection", "yes");

		}

		// Print output files
		ProjectionUtils.putCRS(planeScenario.getNetwork(), crs.getTargetCRS());
		ProjectionUtils.putCRS(planeScenario.getTransitSchedule(), crs.getTargetCRS());
		filterNetworkToSchedule(planeScenario.getNetwork(), planeScenario.getTransitSchedule());
		if (!Files.exists(outputDir)) {
			try {
				Files.createDirectories(outputDir);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		NetworkUtils.writeNetwork(planeScenario.getNetwork(), outputDir.resolve("outputNetwork.xml.gz").toString());
		new MatsimVehicleWriter(planeScenario.getTransitVehicles()).writeFile(outputDir.resolve("outputVehicles.xml.gz").toString());
		new TransitScheduleWriter(planeScenario.getTransitSchedule()).writeFile(outputDir.resolve("outputSchedule.xml.gz").toString());

		return 0;
	}

	/**
	 * Removes links and nodes that are not referenced by the transit schedule while leaving the
	 * schedule itself unchanged. Stop-facility links are retained even when a facility is currently
	 * not used by a route, so every {@code linkRefId} in the schedule remains valid.
	 */
	private void filterNetworkToSchedule(Network network, TransitSchedule schedule) {
		Set<Id<Link>> requiredLinkIds = new HashSet<>();
		for (TransitStopFacility facility : schedule.getFacilities().values()) {
			if (facility.getLinkId() != null) {
				requiredLinkIds.add(facility.getLinkId());
			}
		}

		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute transitRoute : line.getRoutes().values()) {
				NetworkRoute networkRoute = transitRoute.getRoute();
				if (networkRoute == null) {
					throw new IllegalStateException("Transit route " + transitRoute.getId() + " has no network route");
				}
				requiredLinkIds.add(Objects.requireNonNull(networkRoute.getStartLinkId(),
					"Missing start link for transit route " + transitRoute.getId()));
				requiredLinkIds.addAll(networkRoute.getLinkIds());
				requiredLinkIds.add(Objects.requireNonNull(networkRoute.getEndLinkId(),
					"Missing end link for transit route " + transitRoute.getId()));
			}
		}

		Set<Id<Link>> missingLinkIds = new TreeSet<>(Comparator.comparing(Id::toString));
		missingLinkIds.addAll(requiredLinkIds);
		missingLinkIds.removeAll(network.getLinks().keySet());
		if (!missingLinkIds.isEmpty()) {
			throw new IllegalStateException("Transit schedule references missing network links: " + missingLinkIds);
		}

		int linksBefore = network.getLinks().size();
		int nodesBefore = network.getNodes().size();
		network.getLinks().keySet().stream()
			.filter(linkId -> !requiredLinkIds.contains(linkId))
			.toList()
			.forEach(network::removeLink);
		network.getNodes().values().stream()
			.filter(node -> node.getInLinks().isEmpty() && node.getOutLinks().isEmpty())
			.map(Node::getId)
			.toList()
			.forEach(network::removeNode);

		log.info("Filtered plane network to schedule references: {} -> {} links, {} -> {} nodes",
			linksBefore, network.getLinks().size(), nodesBefore, network.getNodes().size());
	}

	private VehicleType createPlaneType(String aircraftType, Double maximumObservedBlockVelocity) {
		VehicleType planeType = VehicleUtils.createVehicleType(Id.createVehicleTypeId(aircraftType), TransportMode.airplane);
		planeType.getAttributes().putAttribute("airplane", "yes");
		planeType.getAttributes().putAttribute("icaoAircraftType", aircraftType);
		if (defaultPassengerCapacity < 1) {
			throw new IllegalArgumentException("seats must be positive for aircraft type " + aircraftType);
		}
		planeType.getCapacity().setSeats(defaultPassengerCapacity);

		if (maximumObservedBlockVelocity != null) {
			planeType.setMaximumVelocity(maximumObservedBlockVelocity);
			planeType.getAttributes().putAttribute("maximumVelocitySource", "maximumObservedBlockVelocity");
		}

		return planeType;
	}

	private void addFlightAttributes(Attributable attributable, FlightInformation flightInformation) {
		Attributes attributes = attributable.getAttributes();
		attributes.putAttribute("icaoAircraftType", flightInformation.aircraftType);
		attributes.putAttribute("icaoOperator", flightInformation.operator);
	}

	private String normalizeCode(String value, String fallback) {
		String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		return normalized.isEmpty() ? fallback : normalized;
	}

	private ShpOptions.Index createAreaIndex(String area) {
		if (area == null || area.isBlank() || area.equalsIgnoreCase("none")) {
			return null;
		}

		return new ShpOptions(area, null, StandardCharsets.UTF_8).createIndex("EPSG:4326", null);
	}

	private boolean isInsideArea(ShpOptions.Index areaIndex, Coord coord) {
		return areaIndex != null && (coord == null || !areaIndex.contains(coord));
	}

	private Coord parseCoordinate(String longitude, String latitude) {
		if (longitude.isBlank() || latitude.isBlank()) {
			return null;
		}

		return new Coord(Double.parseDouble(longitude), Double.parseDouble(latitude));
	}

	private void addAirportToNetwork(Network planeNetwork, Tuple<String, Coord> airport,
		CoordinateTransformation transformation){
		List<Node> airportNodes = new ArrayList<>();
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "runwayInbound"),
			transformAirportOffset(airport.getSecond(), 0, 0, transformation)));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "taxiInbound"),
			transformAirportOffset(airport.getSecond(), 1500, 0, transformation)));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst()),
			transformAirportOffset(airport.getSecond(), 2000, 500, transformation)));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "apron"),
			transformAirportOffset(airport.getSecond(), 2000, 1000, transformation)));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "taxiOutbound"),
			transformAirportOffset(airport.getSecond(), 1500, 1500, transformation)));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "runwayOutbound"),
			transformAirportOffset(airport.getSecond(), 0, 1500, transformation)));

		// Add an attribute to the airport nodes (makes identification of airports easier)
		for(var node : airportNodes){
			node.getAttributes().putAttribute("airport", airport.getFirst());
			planeNetwork.addNode(node);
		}

		List<Link> airportLinks = new ArrayList<>();
		airportLinks.add(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "runwayInbound"), airportNodes.get(0), airportNodes.get(1), planeNetwork, 1500, 1500, 3600, 1));
		airportLinks.add(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "taxiInbound"), airportNodes.get(1), airportNodes.get(2), planeNetwork, 500, 20/3.6, 3600, 1));
		airportLinks.add(NetworkUtils.createLink(Id.createLinkId(airport.getFirst()), airportNodes.get(2), airportNodes.get(3), planeNetwork, 500, 20/3.6, 3600, 1));
		airportLinks.add(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "taxiOutbound"), airportNodes.get(3), airportNodes.get(4), planeNetwork, 500, 500./(90.+boarding_time), 3600, 1));
		airportLinks.add(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "runwayOutbound"), airportNodes.get(4), airportNodes.get(5), planeNetwork, 1500, 1500, 3600, 1));

		// Add an attribute to the airport links (makes identification of airports easier)
		for (var link : airportLinks){
			link.setAllowedModes(AIRPLANE_ALLOWED_MODES);
			link.getAttributes().putAttribute("airport", airport.getFirst());
			planeNetwork.addLink(link);
		}
	}

	private Coord transformAirportOffset(Coord airportWgs84, double eastMeters, double northMeters,
		CoordinateTransformation transformation) {
		Coord offsetWgs84 = airportWgs84;
		double distance = Math.hypot(eastMeters, northMeters);
		if (distance > 0.) {
			GeodeticCalculator calculator = new GeodeticCalculator();
			calculator.setStartingGeographicPoint(airportWgs84.getX(), airportWgs84.getY());
			calculator.setDirection(Math.toDegrees(Math.atan2(eastMeters, northMeters)), distance);
			Point2D destination = calculator.getDestinationGeographicPoint();
			offsetWgs84 = new Coord(destination.getX(), destination.getY());
		}
		return transformation.transform(offsetWgs84);
	}

	/**
	 * Flight data required to create one scheduled departure.
	 */
	private record FlightInformation(String id, String ADEP, String ADES, LocalDateTime filedOffBlockTime,
		LocalDateTime filedArrivalTime, double distance, String aircraftType, String operator) {}

}
