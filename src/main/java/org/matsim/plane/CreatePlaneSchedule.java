package org.matsim.plane;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.*;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * This class provides a parser for the EUROCONTROL flight data and generates a MATSim schedule. <br/><br/>
 *
 * The <i>EUROCONTROL Aviation Data Repository for Research</i> (ADRR) datasets are available <a href="https://www.eurocontrol.int/dashboard/aviation-data-research"/>here<a/>.
 * This class only needs the basic <i>Flight</i> dataset. The data is free for Research and development purposes, updated every quarter and includes
 * all flights from a given month two years prior. <i>Example: The currently used dataset for 12/2023 was released in 01/2026.</i> <br/><br/>
 *
 * This class also generates a plane-network, containing airports and connection links which can be later fused with the given scenario network
 * using the {@link AirportNetworkFuser}.
 */
public class CreatePlaneSchedule implements MATSimAppCommand {
	// TODO Vehicle Types are a problem, no free data for ICAO codes available!

	private static final Logger log = LogManager.getLogger(CreatePlaneSchedule.class);

	@CommandLine.Option(names = "--adrr-flight-data", description = "Path to the flights file of the EUROCONTROL ADRR dataset", required = true)
	private String flightPath;

	@CommandLine.Option(names = "--sample-day", description = "The day to sample from the dataset. Make sure to give a date with no holidays or other unusual events. Format: DD-MM-YYYY", required = true)
	private String sampleDay;

	@CommandLine.Option(names = "--network-output", description = "Path to output network", required = true)
	private String outputNetwork;

	@CommandLine.Option(names = "--schedule-output", description = "Path to output schedule", required = true)
	private String outputSchedule;

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
		CoordinateTransformation transformation = crs.getTransformation();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

		var format = CSVFormat.DEFAULT.builder()
			.setDelimiter(",")
			.setSkipHeaderRecord(true)
			.setHeader()
			.build();

		try (var reader = Files.newBufferedReader(Path.of(flightPath)); var parser = CSVParser.parse(reader, format)) {
			for(var record : parser){
				flightInformationList.add( new FlightInformation(
					record.get("ECTRL ID"),
					record.get("ADEP"),
					record.get("ADES"),
					LocalDateTime.parse(record.get("FILED OFF BLOCK TIME"), formatter),
					LocalDateTime.parse(record.get("FILED ARRIVAL TIME"), formatter),
					Double.parseDouble(record.get("Actual Distance Flown (nm)"))*1852
				));

				String lonADEP = record.get("ADEP Longitude");
				String latADEP = record.get("ADEP Latitude");
				String lonADES = record.get("ADES Longitude");
				String latADES = record.get("ADES Latitude");

				if(!lonADEP.isEmpty() && !latADEP.isEmpty()){
					airportSet.add(new Tuple<>(record.get("ADEP"), transformation.transform(new Coord(Double.parseDouble(lonADEP), Double.parseDouble(latADEP)))));
				} else {
					log.warn("Skipped ADEP airport {} due to missing coordinates!", record.get("ADEP"));
				}

				if(!lonADES.isEmpty() && !latADES.isEmpty()) {
					airportSet.add(new Tuple<>(record.get("ADES"), transformation.transform(new Coord(Double.parseDouble(lonADES), Double.parseDouble(latADES)))));
				} else {
					log.warn("Skipped ADES airport {} due to missing coordinates!", record.get("ADES"));
				}
			}
		} catch(IOException e){
			throw new RuntimeException(e);
		}

		// Prepare Scenario
		Scenario planeScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitScheduleFactoryImpl scheduleFactory = new TransitScheduleFactoryImpl();

		// Create the plane-network and add airports into it (without connections)
		for(var airport : airportSet){
			addAirportToNetwork(planeScenario.getNetwork(), airport);
			planeScenario.getTransitSchedule().addStopFacility(scheduleFactory.createTransitStopFacility(
				Id.create(airport.getFirst(), TransitStopFacility.class),
				CoordUtils.plus(airport.getSecond(), new Coord(2000, 500)),
				false
			));
		}

		// Create schedule with given flightInformation
		// TODO
		for(var flightInformation : flightInformationList){
			String id = flightInformation.ADEP + "_" + flightInformation.ADES + "_" + flightInformation.id;
			Id<Link> linkId = Id.createLinkId(id);
			Id<TransitLine> lineId = Id.create(id, TransitLine.class);
			Id<TransitRoute> routeId = Id.create(id, TransitRoute.class);

			// Add the link for this flight
			double time = ChronoUnit.SECONDS.between(flightInformation.filedOffBlockTime, flightInformation.filedArrivalTime);

			planeScenario.getNetwork().addLink(NetworkUtils.createLink(
				linkId,
				planeScenario.getNetwork().getNodes().get(Id.createNodeId(flightInformation.ADEP + "runwayOutbound")),
				planeScenario.getNetwork().getNodes().get(Id.createNodeId(flightInformation.ADES + "runwayInbound")),
				planeScenario.getNetwork(),
				flightInformation.distance,
				flightInformation.distance / time,
				3600,
				1
			));

			// Add the transitLine

			planeScenario.getTransitSchedule().addTransitLine(scheduleFactory.createTransitLine(lineId));

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
				scheduleFactory.createTransitRouteStop(planeScenario.getTransitSchedule().getFacilities().get(Id.create(flightInformation.ADEP, TransitStopFacility.class)), 0, 0),
				scheduleFactory.createTransitRouteStop(planeScenario.getTransitSchedule().getFacilities().get(Id.create(flightInformation.ADEP, TransitStopFacility.class)), time, time)
			);

			planeScenario.getTransitSchedule().getTransitLines().get(lineId).addRoute(scheduleFactory.createTransitRoute(routeId, route, stops, "pt"));
		}

		// Print output files
		NetworkUtils.writeNetwork(planeScenario.getNetwork(), outputNetwork);
		new TransitScheduleWriter(planeScenario.getTransitSchedule()).writeFile(outputSchedule);

		return (Integer) 0;
	}

	private void addAirportToNetwork(Network planeNetwork, Tuple<String, Coord> airport){
		List<Node> airportNodes = new ArrayList<>();
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "runwayInbound"), airport.getSecond()));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "taxiInbound"), CoordUtils.plus(airport.getSecond(), new Coord(1500, 0))));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() ), CoordUtils.plus(airport.getSecond(), new Coord(2000, 500))));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "apron"), CoordUtils.plus(airport.getSecond(), new Coord(2000, 1000))));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "taxiOutbound"), CoordUtils.plus(airport.getSecond(), new Coord(1500, 1500))));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "runwayOutbound"), CoordUtils.plus(airport.getSecond(), new Coord(0, 1500))));

		for(var node : airportNodes){
			planeNetwork.addNode(node);
		}

		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "runwayInbound"), airportNodes.get(0), airportNodes.get(1), planeNetwork, 1500, 1500, 3600, 1));
		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "taxiInbound"), airportNodes.get(1), airportNodes.get(2), planeNetwork, 500, 20/3.6, 3600, 1));
		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst()), airportNodes.get(2), airportNodes.get(3), planeNetwork, 500, 20/3.6, 3600, 1));
		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "taxiOutbound"), airportNodes.get(3), airportNodes.get(4), planeNetwork, 500, 20/3.6, 3600, 1));
		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "runwayOutbound"), airportNodes.get(4), airportNodes.get(5), planeNetwork, 1500, 1500, 3600, 1));
	}

	record FlightInformation(String id, String ADEP, String ADES, LocalDateTime filedOffBlockTime, LocalDateTime filedArrivalTime, double distance){}
}
