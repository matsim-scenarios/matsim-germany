package org.matsim.demand;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.IndividualRaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorParametersForPerson;
import com.google.inject.Injector;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.XY2Links;
import org.matsim.core.population.io.StreamingPopulationWriter;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.run.RunGermany;
import org.matsim.utils.objectattributes.ObjectAttributesConverter;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** One OD trip per person, routed by car, train and airplane using existing injectors. */
public final class CreateChoiceSet {

	private static final List<String> MODES = List.of(TransportMode.car, TransportMode.train, TransportMode.airplane);
	private static final Set<String> RAIL = Set.of(TransportMode.train, "longDistanceTrain", "regionalTrain");
	private static final double EXCLUDED_TRANSIT_COST = 1e100;
	private final Scenario scenario, trainScenario, airplaneScenario;
	private final TripRouter trainRouter, airplaneRouter;
	private final ObjectAttributesConverter attributeConverter = new ObjectAttributesConverter();

	CreateChoiceSet(Scenario scenario, Injector train, Injector airplane) {
		this.scenario = scenario;
		trainRouter = train.getInstance(TripRouter.class);
		airplaneRouter = airplane.getInstance(TripRouter.class);
		trainScenario = train.getInstance(Scenario.class);
		airplaneScenario = airplane.getInstance(Scenario.class);
	}

	void write(Path directory) throws IOException {
		var persons = scenario.getPopulation().getPersons().values().stream()
				.sorted(Comparator.comparing(person -> person.getId().toString())).toList();
		var personAttributes = new TreeSet<String>();
		var planAttributes = new TreeSet<String>();
		for (Person person : persons) {
			personAttributes.addAll(person.getAttributes().getAsMap().keySet());
			planAttributes.addAll(person.getSelectedPlan().getAttributes().getAsMap().keySet());
		}
		Files.createDirectories(directory);

		var plans = new StreamingPopulationWriter();
		plans.startStreaming(directory.resolve("choice-plans.xml.gz").toString());
		try (var csv = new CSVPrinter(Files.newBufferedWriter(directory.resolve("choice-set.csv")), CSVFormat.DEFAULT)) {
			csv.printRecord(csvHeader(personAttributes, planAttributes));
			for (Person person : persons) {
				var alternatives = scenario.getPopulation().getFactory().createPerson(person.getId());
				AttributesUtils.copyAttributesFromTo(person, alternatives);
				var row = computeAlternatives(person, alternatives);
				personAttributes.forEach(key -> row.add(attributeValue(person.getAttributes().getAttribute(key))));
				planAttributes.forEach(key -> row.add(attributeValue(person.getSelectedPlan().getAttributes().getAttribute(key))));
				plans.run(alternatives);
				csv.printRecord(row);
			}
		} finally {
			plans.closeStreaming();
		}
	}

	private List<Object> computeAlternatives(Person person, Person alternatives) {
		var trip = TripStructureUtils.getTrips(person.getSelectedPlan()).getFirst();
		double departure = trip.getOriginActivity().getEndTime().seconds();
		var origin = FacilitiesUtils.toFacility(trip.getOriginActivity(), scenario.getActivityFacilities());
		var destination = FacilitiesUtils.toFacility(trip.getDestinationActivity(), scenario.getActivityFacilities());
		String observedMode = observedMode(person, trip);
		var routingAttributes = routingAttributes(person);
		var values = new ArrayList<Object>();
		boolean eligible = false;

		for (String mode : MODES) {
			boolean flying = mode.equals(TransportMode.airplane);
			TripRouter router = flying ? airplaneRouter : trainRouter;
			Scenario context = flying ? airplaneScenario : trainScenario;
			List<? extends PlanElement> route = List.of();
			if (mode.equals(TransportMode.car) || !context.getTransitSchedule().getTransitLines().isEmpty()) {
				route = router.calcRoute(mode.equals(TransportMode.car) ? TransportMode.car : "longDistancePt",
						origin, destination, departure, person, routingAttributes);
			}

			double arrival = departure, distance = 0;
			boolean valid = false;
			for (PlanElement element : route) {
				if (element instanceof Activity activity) {
					if (activity.getEndTime().isDefined()) arrival = Math.max(arrival, activity.getEndTime().seconds());
					else arrival += activity.getMaximumDuration().orElse(0);
				} else if (element instanceof Leg leg) {
					double duration = leg.getTravelTime().seconds();
					var path = leg.getRoute();
					arrival = Math.max(arrival, leg.getDepartureTime().orElse(arrival)) + duration;
					distance += path.getDistance();
					String legMode = mode(leg, context);
					if (RAIL.contains(legMode)) legMode = TransportMode.train;
					if (leg.getRoute() instanceof TransitPassengerRoute && !mode.equals(legMode)) {
						valid = false;
						break;
					}
					if (mode.equals(legMode)) valid = true;
				}
			}
			if (!valid) {
				values.addAll(List.of(0, "", ""));
				continue;
			}

			var plan = scenario.getPopulation().getFactory().createPlan();
			AttributesUtils.copyAttributesFromTo(person.getSelectedPlan(), plan);
			plan.setType(mode);
			plan.addActivity(PopulationUtils.createActivity(trip.getOriginActivity()));
			plan.getPlanElements().addAll(route);
			var destinationActivity = PopulationUtils.createActivity(trip.getDestinationActivity());
			destinationActivity.setStartTime(arrival);
			plan.addActivity(destinationActivity);
			alternatives.addPlan(plan);
			if (mode.equals(observedMode)) {
				alternatives.setSelectedPlan(plan);
				eligible = true;
			}
			values.addAll(List.of(1, arrival - departure, distance));
		}

		String choiceSource = person.getAttributes().getAttribute("vp.sourceMode") == null
				? "selected_plan" : "verflechtungsprognose";
		var row = new ArrayList<Object>(List.of(person.getId().toString(), MODES.indexOf(observedMode) + 1,
				choiceSource, eligible ? 1 : 0, departure, trip.getDestinationActivity().getType()));
		row.addAll(values);
		return row;
	}

	private AttributesImpl routingAttributes(Person person) {
		var vehicles = scenario.getVehicles();
		var qsim = scenario.getConfig().qsim();
		var id = VehicleUtils.hasVehicleId(person, TransportMode.car) ? VehicleUtils.getVehicleId(person, TransportMode.car)
				: qsim.getUsePersonIdForMissingVehicleId() ? Id.createVehicleId(person.getId()) : VehicleUtils.createVehicleId(person, TransportMode.car);
		if (!vehicles.getVehicles().containsKey(id)) {
			VehicleType type = switch (qsim.getVehiclesSource()) {
				case defaultVehicle -> VehicleUtils.createDefaultVehicleType();
				case modeVehicleTypesFromVehiclesData -> vehicles.getVehicleTypes().get(Id.create(TransportMode.car, VehicleType.class));
				case fromVehiclesData -> throw new IllegalArgumentException("Missing car vehicle in scenario: " + id);
			};
			var personalTypes = VehicleUtils.getVehicleTypes(person);
			if (personalTypes != null && personalTypes.containsKey(TransportMode.car)) type = vehicles.getVehicleTypes().get(personalTypes.get(TransportMode.car));
			if (type == null) throw new IllegalArgumentException("Missing car vehicle type for " + person.getId());
			if (!vehicles.getVehicleTypes().containsKey(type.getId())) vehicles.addVehicleType(type);
			vehicles.addVehicle(VehicleUtils.createVehicle(id, vehicles.getVehicleTypes().get(type.getId())));
		}
		var attributes = new AttributesImpl();
		attributes.putAttribute(DefaultRoutingRequest.ATTRIBUTE_VEHICLE_ID, id);
		return attributes;
	}

	private static List<String> csvHeader(Set<String> personAttributes, Set<String> planAttributes) {
		var header = new ArrayList<>(List.of("person_id", "choice", "choice_source", "estimation_eligible", "departure_time_s", "purpose"));
		for (String mode : MODES) {
			for (String field : List.of("avail", "time_s", "distance_m")) header.add(field + "_" + mode);
		}
		personAttributes.forEach(key -> header.add("person_attribute:" + key));
		planAttributes.forEach(key -> header.add("plan_attribute:" + key));
		return header;
	}

	private String attributeValue(Object value) {
		return value == null ? "" : Objects.requireNonNull(attributeConverter.convertToString(value));
	}

	private String observedMode(Person person, TripStructureUtils.Trip trip) {
		Object source = person.getAttributes().getAttribute("vp.sourceMode");
		if (source != null) return switch (source.toString()) {
			case "MIV" -> TransportMode.car;
			case "Bahn" -> TransportMode.train;
			case "Luft" -> TransportMode.airplane;
			default -> "";
		};
		var modes = TripStructureUtils.getLegs(trip.getTripElements()).stream().map(leg -> mode(leg, scenario)).toList();
		if (modes.contains(TransportMode.airplane)) return TransportMode.airplane;
		if (modes.stream().anyMatch(RAIL::contains)) return TransportMode.train;
		boolean carTrip = modes.contains(TransportMode.car) && modes.stream()
				.allMatch(Set.of(TransportMode.car, TransportMode.walk, TransportMode.transit_walk, TransportMode.non_network_walk)::contains);
		return carTrip ? TransportMode.car : "";
	}

	static String mode(Leg leg, Scenario scenario) {
		if (leg.getRoute() instanceof TransitPassengerRoute route) {
			return scenario.getTransitSchedule().getTransitLines().get(route.getLineId()).getRoutes().get(route.getRouteId()).getTransportMode();
		}
		return leg.getMode();
	}

	public static void main(String[] args) throws IOException {
		create(RunGermany.prepareScenario(args));
	}

	public static void create(Config config) throws IOException {
		create(ScenarioUtils.loadScenario(config));
	}

	public static void create(Scenario scenario) throws IOException {
		var config = scenario.getConfig();
		ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class).setUseRangeQuery(false);
		new XY2Links(scenario).run(scenario.getPopulation());
		var directory = Path.of(config.controller().getOutputDirectory()).resolve("choice-set");
		var output = new OutputDirectoryHierarchy(directory.toString(), config.controller().getOverwriteFileSetting(),
				config.controller().getCompressionType());
		var train = injector(scenario, false, output);
		var airplane = injector(scenario, true, output);
		new CreateChoiceSet(scenario, train, airplane).write(directory);
	}

	private static Injector injector(Scenario scenario, boolean airplane, OutputDirectoryHierarchy output) {
		var blockedModes = scenario.getTransitSchedule().getTransitLines().values().stream()
				.flatMap(line -> line.getRoutes().values().stream()).map(route -> route.getTransportMode()).distinct()
				.filter(mode -> airplane ? !mode.equals(TransportMode.airplane) : !RAIL.contains(mode)).toList();
		return new org.matsim.core.controler.Injector.InjectorBuilder(scenario).addStandardModules()
				.addOverridingModule(new AbstractModule() {
					@Override public void install() {
						bind(OutputDirectoryHierarchy.class).toInstance(output);
						addTravelTimeBinding(TransportMode.car).to(FreeSpeedTravelTime.class);
						// A fixed finite cost also excludes zero-second segments without 0 * Infinity.
						bind(RaptorInVehicleCostCalculator.class).toInstance((time, utility, person, vehicle, parameters, iterator) ->
								utility == -EXCLUDED_TRANSIT_COST ? EXCLUDED_TRANSIT_COST : -time * utility);
						bind(IndividualRaptorParametersForPerson.class);
						var defaults = binder().getProvider(IndividualRaptorParametersForPerson.class);
						bind(RaptorParametersForPerson.class).toProvider(() -> {
							var base = defaults.get();
							return person -> {
								var parameters = base.getRaptorParameters(person);
								blockedModes.forEach(mode -> parameters.setMarginalUtilityOfTravelTime_utl_s(mode, -EXCLUDED_TRANSIT_COST));
								return parameters;
							};
						});
					}
				}).build();
	}
}
