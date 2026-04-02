/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.project.zerocuts;

import com.google.common.base.Verify;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCalcTopoType;
import org.matsim.core.network.algorithms.NetworkSimplifier;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.population.algorithms.PersonPrepareForSim;
import org.matsim.core.population.routes.GenericRouteImpl;
import org.matsim.core.replanning.modules.ReRoute;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.population.algorithms.XY2Links;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.prepare.CreateNetworkFromOSM;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiPredicate;

import static org.matsim.prepare.longDistanceFreightGER.GenerateFreightPlans.*;

public class RouteRailFreightOnElectrifiedNetwork implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-network", description = "input network", required = true,
//		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-network.xml.gz")
	defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-network-railways-final.xml.gz")

	private Path inputNetwork;

	@CommandLine.Option(names = "--input-freight-plans", description = "input freight plans", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-0.1pct.plans.xml.gz")
	private Path inputFreightPlans;

	@CommandLine.Option(names = "--output", description = "output csv file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/freight-rail_routes-on-electrified-network-analysis.csv")
	private Path output;

	@CommandLine.Option(names = "--outputPlans", description = "output plans file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/freight-rail_routes-on-electrified-network-analysis_plans")
	private Path outputPlans;

	@CommandLine.Option(names = "--outputRun", description = "output directory run", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/freight-rail_routes-on-electrified-network-analysis_run/")
	private Path outputRun;

	private com.google.inject.Injector injector;
	private TripRouter tripRouter;
	private String columnSeparator = ",";
	private static final Logger log = LogManager.getLogger(RouteRailFreightOnElectrifiedNetwork.class);

	static final String[] HEADER = {"fromX", "fromY", "toX", "toY", "fromLink", "toLink",
		"pre-run_mode", "main-run_mode", "post-run_mode", "initial_origin_cell", "origin_cell_main_run", "destination_cell_main_run",
		"final_destination_cell", "origin_cell_main_run_in_germany", "destination_cell_main_run_in_germany", "goods_type", "tons_year",
		"person_id", "length_access_km", "length_egress_km",
		"length_non_electrified_km", "length_electrified_km", "length_electrified_incl_proposed_km"};

	public static void main(String[] args) {
		new RouteRailFreightOnElectrifiedNetwork().execute(args);
	}

	public Integer call() throws Exception {
		// interesting agents are for example
		// longDistanceFreight_76766_0_main (vehicle longDistanceFreight_76766_0_main_rail): illogical detour within non-electrfied route near Stuttgart
		// longDistanceFreight_27272_0_main: very short trip, both routes result on all electrified rail lines ("contact_line"), but the "non-electrified" route is longer

		Config config = ConfigUtils.createConfig();//ConfigUtils.loadConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
		// it manages to fail due to the output directory although no simulation is run and consequentially no output should be written
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.network().setInputFile(inputNetwork.toString());
		config.plans().setInputFile(inputFreightPlans.toString());
		Set<String> railwayModes = Set.of(OsmTags.RAIL, CreateNetworkFromOSM.RAIL_ELECTRIFIED, CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED);
		config.routing().setNetworkModes(railwayModes); // this hopefully sets up network routing modules
		config.routing().getBeelineDistanceFactors().put(TransportMode.walk, 1.0d); // we want pure beeline
		config.routing().setRoutingRandomness(0.0);
		config.scoring().setPerforming_utils_hr(0.0);
		ScoringConfigGroup.ActivityParams freightStartActivityParams = new ScoringConfigGroup.ActivityParams("freight_start");
		freightStartActivityParams.setTypicalDuration(3600);
		config.scoring().addActivityParams(freightStartActivityParams);
		ScoringConfigGroup.ActivityParams freightEndActivityParams = new ScoringConfigGroup.ActivityParams("freight_end");
		freightEndActivityParams.setTypicalDuration(3600);
		config.scoring().addActivityParams(freightEndActivityParams);
		ScoringConfigGroup.ModeParams scoreFreightRail = new ScoringConfigGroup.ModeParams(LEG_MODE_FREIGHT_RAIL);
		scoreFreightRail.setMarginalUtilityOfTraveling(0.0);
		scoreFreightRail.setMonetaryDistanceRate(-0.1);
		ScoringConfigGroup.ModeParams scoreRailElectrified = new ScoringConfigGroup.ModeParams(CreateNetworkFromOSM.RAIL_ELECTRIFIED);
		scoreRailElectrified.setMarginalUtilityOfTraveling(0.0);
		scoreRailElectrified.setMonetaryDistanceRate(-0.1);
		ScoringConfigGroup.ModeParams scoreRailElectrifiedInclProposed = new ScoringConfigGroup.ModeParams(CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED);
		scoreRailElectrifiedInclProposed.setMarginalUtilityOfTraveling(0.0);
		scoreRailElectrifiedInclProposed.setMonetaryDistanceRate(-0.1);

		config.scoring().addModeParams(scoreFreightRail);
		config.scoring().addModeParams(scoreRailElectrified);
		config.scoring().addModeParams(scoreRailElectrifiedInclProposed);

		Scenario scenario = ScenarioUtils.createScenario(config);
		Set<String> modesThatNeedVehicleTypes = new HashSet<>();
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_ROAD);
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_RAIL);
		modesThatNeedVehicleTypes.addAll(railwayModes);

		ScenarioUtils.loadScenario(scenario);

		// normal NetworkSimplifier is not helpful:
		// default is networkSimplifier.setMergeLinkStats(false): this merges only a small share of links
		// networkSimplifier.setMergeLinkStats(true) merges many links, but drops the allowed modes attribute, and after cleaning the electrified network shrank to a single station
		// the custom IsMergeablePredicate as tried out below is not working
//		NetworkSimplifier networkSimplifier = NetworkSimplifier.createNetworkSimplifier(scenario.getNetwork());
//		BiPredicate<Link, Link> linksMergeablePredicate = (link1, link2) ->
//		{
//			if (//link1.getAllowedModes().equals(link2.getAllowedModes())
//				link1.getAllowedModes().containsAll(link2.getAllowedModes()) && link2.getAllowedModes().containsAll(link1.getAllowedModes())
//			 )
//				return true;
//			return false;
//		};
//		networkSimplifier.registerIsMergeablePredicate(linksMergeablePredicate);
////		networkSimplifier.setMergeLinkStats(true);
//		networkSimplifier.run(scenario.getNetwork());
//		NetworkUtils.cleanNetwork(scenario.getNetwork(), railwayModes);
//		NetworkWriter networkWriter = new NetworkWriter(scenario.getNetwork());
//		networkWriter.write("../shared-svn/projects/matsim-germany/zerocuts2/network_railway_final_simplified.xml.gz");

		Map<String, Id<VehicleType>> modeToVehicleType = new HashMap<>();
		for (String mode: modesThatNeedVehicleTypes) {
			VehicleType vehicleType = scenario.getVehicles().addModeVehicleType(mode);
			vehicleType.setMaximumVelocity(80/3.6);
			modeToVehicleType.put(mode, vehicleType.getId());
		}

		List<Person> personsToAdd = new ArrayList<>();
		PopulationFactory populationFactory = scenario.getPopulation().getFactory();
		for (Person person: scenario.getPopulation().getPersons().values()) {
			Person electrifiedRoutePerson = populationFactory.createPerson(Id.createPersonId(person.getId().toString() + "_electrified"));
			Plan electrifiedRoutePlan = populationFactory.createPlan();
			electrifiedRoutePerson.addPlan(electrifiedRoutePlan);
			personsToAdd.add(electrifiedRoutePerson);

			Person electrifiedInclProposedRoutePerson = populationFactory.createPerson(Id.createPersonId(person.getId().toString() + "_electrifiedInclProposed"));
			Plan electrifiedInclProposedRoutePlan = populationFactory.createPlan();
			electrifiedInclProposedRoutePerson.addPlan(electrifiedInclProposedRoutePlan);
			personsToAdd.add(electrifiedInclProposedRoutePerson);

			for (PlanElement planElement: person.getSelectedPlan().getPlanElements()) {
				if (planElement instanceof Activity) {
					Activity activity = (Activity) planElement;
					electrifiedRoutePlan.addActivity(populationFactory.createActivityFromCoord(activity.getType(), activity.getCoord()));
					electrifiedInclProposedRoutePlan.addActivity(populationFactory.createActivityFromCoord(activity.getType(), activity.getCoord()));
				} else {
					electrifiedRoutePlan.addLeg(populationFactory.createLeg(CreateNetworkFromOSM.RAIL_ELECTRIFIED));
					electrifiedInclProposedRoutePlan.addLeg(populationFactory.createLeg(CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED));
				}
			}
		}

		for (Person person: personsToAdd) {
			scenario.getPopulation().addPerson(person);
		}

		// Since this is not running PersonPrepareForSim, we have to manually add all VehicleTypes to all Persons (it failed without), and even then
		// it failed with "Could not retrieve vehicle id from person: longDistanceFreight_0_0_main", so explicitly add Vehicles...
		for (Person person: scenario.getPopulation().getPersons().values()) {
//			VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, modeToVehicleType);
			Map<String, Id<Vehicle>> modeToVehicle = new HashMap<>();
			for (Map.Entry<String, Id<VehicleType>> modeVehicleType: modeToVehicleType.entrySet()) {
				Id<Vehicle> vehicleId = VehicleUtils.createVehicleId(person, modeVehicleType.getKey());
				Vehicle vehicle = scenario.getVehicles().getFactory().createVehicle(vehicleId, scenario.getVehicles().getVehicleTypes().get(modeVehicleType.getValue()));
				scenario.getVehicles().addVehicle(vehicle);
				modeToVehicle.put(modeVehicleType.getKey(), vehicleId);
			}
			VehicleUtils.insertVehicleIdsIntoAttributes(person, modeToVehicle);
		}

		this.injector = new Injector.InjectorBuilder( scenario )
			.addStandardModules()
			.build();
		tripRouter = injector.getInstance( TripRouter.class );

		/*
		 * Route and analyze on 3 different filtered networks: all railways, electrified incl. proposed electrification, already electrified.
		 * This uses the pre-attributed and cleaned networks from CreateNetworkFromOSM.
		 * In order to make results comparable, map all activities to the nearest link on the already the electrified railway network.
		 * Track classes (maximum weight / axle) is not sufficiently tagged yet and therefore ignored. Also gradient is not sufficiently tagged
		 */
		NetworkFilterManager filterElectrified = new NetworkFilterManager(scenario.getNetwork(), new NetworkConfigGroup());
		filterElectrified.addLinkFilter(l -> {
			if (l.getAllowedModes().contains(CreateNetworkFromOSM.RAIL_ELECTRIFIED)) {
				return true;
			}
			return false;
		});
		Network electrifiedRailwayNetwork = filterElectrified.applyFilters();

		CSVPrinter printer = null;
		try {printer = new CSVPrinter(IOUtils.getBufferedWriter(output.toString()),
			CSVFormat.Builder.create()
				.setDelimiter(columnSeparator)
				.setHeader(HEADER)
				.build());
		} catch (IOException e) {
			log.error(e);
		}

		XY2Links xy2Links = new XY2Links(electrifiedRailwayNetwork, scenario.getActivityFacilities());
		int counterAll = 0;
		int counterRailTrips = 0;
		int nextMsg=1;
		double sumTons = 0.0;
		double accessLegLengthNonElectrifiedKm = 0.0;
		double egressLegLengthNonElectrifiedKm = 0.0;
		double sumLengthNonElectrifiedKm = 0.0;
		double sumLengthElectrifiedKm = 0.0;
		double sumLengthElectrifiedOrProposedKm = 0.0;
		double accessLegLengthNonElectrifiedWeighted = 0.0;
		double egressLegLengthNonElectrifiedWeighted = 0.0;
		double sumLengthNonElectrifiedWeighted = 0.0;
		double sumLengthElectrifiedWeighted = 0.0;
		double sumLengthElectrifiedOrProposedWeighted = 0.0;

		for (Person person: scenario.getPopulation().getPersons().values()) {
			if (person.getId().toString().endsWith("_electrified") || person.getId().toString().endsWith("_electrifiedInclProposed")) { continue;}
			counterAll++;
			if (counterAll % nextMsg == 0) {
				nextMsg *= 4;
				log.info(" routed person # " + counterAll);}
			if (!person.getId().toString().contains(LONG_DISTANCE_FREIGHT)) {continue;}
			xy2Links.run(person);
			// The correct input file should have one plan per person with one trip and one leg each.
			// If that changes this analysis class needs to be adapted.
			Verify.verify(person.getPlans().size() == 1, person.getId() + " has more than 1 plan.");
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
			Verify.verify(trips.size() == 1, person.getId() + " has more than 1 trip.");
			TripStructureUtils.Trip trip = trips.get(0);
			Verify.verify(trip.getLegsOnly().size() == 1, person.getId() + " has more than 1 leg before routing.");
			Leg singleLeg = trip.getLegsOnly().get(0);
			if (!singleLeg.getMode().equals(LEG_MODE_FREIGHT_RAIL)) {continue;}
			counterRailTrips++;

			Facility originFacility = FacilitiesUtils.wrapActivity(trip.getOriginActivity());
			Facility destinationFacility = FacilitiesUtils.wrapActivity(trip.getDestinationActivity());

			Person electrifiedRoutePerson = scenario.getPopulation().getPersons().get(Id.createPersonId(person.getId().toString() + "_electrified"));
			Person electrifiedInclProposedRoutePerson = scenario.getPopulation().getPersons().get(Id.createPersonId(person.getId().toString() + "_electrifiedInclProposed"));

			List<PlanElement> routeNonElectrified = (List<PlanElement>) tripRouter.calcRoute(
				OsmTags.RAIL, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
			Leg accessLeg = (Leg) routeNonElectrified.get(0);
			Leg egressLeg = (Leg) routeNonElectrified.get(4);
			Leg nonElectrifiedLeg = (Leg) routeNonElectrified.get(2);
			List<PlanElement> routeElectrified = (List<PlanElement>) tripRouter.calcRoute(
				CreateNetworkFromOSM.RAIL_ELECTRIFIED, originFacility, destinationFacility, 0.0d, electrifiedRoutePerson, trip.getTripAttributes());
			Leg electrifiedLeg = (Leg) routeElectrified.get(2);
			List<PlanElement> routeElectrifiedInclProposed = (List<PlanElement>) tripRouter.calcRoute(
				CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED, originFacility, destinationFacility, 0.0d, electrifiedInclProposedRoutePerson, trip.getTripAttributes());
			Leg electrifiedInclProposedLeg = (Leg) routeElectrifiedInclProposed.get(2);

			// mode railway occurs in input survey data only on main run
			boolean originCellMainRunInGermany = cellInGermany((String) person.getAttributes().getAttribute("origin_cell_main_run"));
			boolean destinationCellMainRunInGermany = cellInGermany((String) person.getAttributes().getAttribute("destination_cell_main_run"));
			double tonsPerYear = (double) person.getAttributes().getAttribute("tons_per_year");

			if (originCellMainRunInGermany) {
				accessLegLengthNonElectrifiedKm += accessLeg.getRoute().getDistance() / 1000;
				accessLegLengthNonElectrifiedWeighted += accessLeg.getRoute().getDistance() * tonsPerYear / 1000;
			}
			if (destinationCellMainRunInGermany) {
				egressLegLengthNonElectrifiedKm += egressLeg.getRoute().getDistance() / 1000;
				egressLegLengthNonElectrifiedWeighted += egressLeg.getRoute().getDistance() * tonsPerYear / 1000;
			}
			if (originCellMainRunInGermany && destinationCellMainRunInGermany) {
				sumLengthNonElectrifiedKm += nonElectrifiedLeg.getRoute().getDistance() / 1000;
				sumLengthElectrifiedKm += electrifiedLeg.getRoute().getDistance() / 1000;
				sumLengthElectrifiedOrProposedKm += electrifiedInclProposedLeg.getRoute().getDistance() / 1000;
				sumLengthNonElectrifiedWeighted += nonElectrifiedLeg.getRoute().getDistance() * tonsPerYear / 1000;
				sumLengthElectrifiedWeighted += electrifiedLeg.getRoute().getDistance() * tonsPerYear / 1000;
				sumLengthElectrifiedOrProposedWeighted += electrifiedInclProposedLeg.getRoute().getDistance() * tonsPerYear / 1000;
				sumTons += (double) person.getAttributes().getAttribute("tons_per_year");
			}

			if (!outputPlans.equals("")) {
				PopulationFactory factory = scenario.getPopulation().getFactory();
				Plan plan = person.getSelectedPlan();
				// copy Person since Via only shows selected plan, not alternative plans
				plan.getPlanElements().clear();
				plan.addActivity(trip.getOriginActivity());
				plan.getPlanElements().addAll(routeNonElectrified);
				plan.addActivity(trip.getDestinationActivity());

				Plan electrifiedPlan = electrifiedRoutePerson.getSelectedPlan();
				electrifiedPlan.getPlanElements().clear();
				electrifiedPlan.addActivity(trip.getOriginActivity());
				electrifiedPlan.getPlanElements().addAll(routeElectrified);
				electrifiedPlan.addActivity(trip.getDestinationActivity());

				Plan electrifiedInclProposedPlan = electrifiedInclProposedRoutePerson.getSelectedPlan();
				electrifiedInclProposedPlan.getPlanElements().clear();
				electrifiedInclProposedPlan.addActivity(trip.getOriginActivity());
				electrifiedInclProposedPlan.getPlanElements().addAll(routeElectrifiedInclProposed);
				electrifiedInclProposedPlan.addActivity(trip.getDestinationActivity());
			}

			try {
				printer.print(originFacility.getCoord().getX());
				printer.print(originFacility.getCoord().getY());
				printer.print(destinationFacility.getCoord().getX());
				printer.print(destinationFacility.getCoord().getY());
				printer.print(originFacility.getLinkId());
				printer.print(destinationFacility.getLinkId());
				printer.print(person.getAttributes().getAttribute("pre-run_mode"));
				printer.print(person.getAttributes().getAttribute("main-run_mode"));
				printer.print(person.getAttributes().getAttribute("post-run_mode"));
				printer.print(person.getAttributes().getAttribute("initial_origin_cell"));
				printer.print(person.getAttributes().getAttribute("origin_cell_main_run"));
				printer.print(person.getAttributes().getAttribute("destination_cell_main_run"));
				printer.print(person.getAttributes().getAttribute("final_destination_cell"));
				printer.print(originCellMainRunInGermany);
				printer.print(destinationCellMainRunInGermany);
				printer.print(person.getAttributes().getAttribute("goods_type"));
				printer.print(person.getAttributes().getAttribute("tons_per_year"));
				printer.print(person.getId().toString());
				printer.print(accessLeg.getRoute().getDistance() / 1000);
				printer.print(egressLeg.getRoute().getDistance() / 1000);
				printer.print(nonElectrifiedLeg.getRoute().getDistance() / 1000);
				printer.print(electrifiedLeg.getRoute().getDistance() / 1000);
				printer.print(electrifiedInclProposedLeg.getRoute().getDistance() / 1000);
				printer.println();
			} catch (IOException e) {
				log.error(e);
			}
		}

		printer.flush();
		printer.close();

		if (!outputPlans.equals("")) {
			PopulationWriter populationWriter = new PopulationWriter(scenario.getPopulation());
			populationWriter.write(outputPlans.toString() + ".xml.gz");
		}

		if (!outputRun.equals("")) {
			Controler controller = new Controler(scenario);
			config.controller().setOutputDirectory(outputRun.toString());
			config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
			ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
			strategySettings.setStrategyName("ChangeExpBeta");
			strategySettings.setWeight(1.0);
			strategySettings.setSubpopulation("longDistanceFreight");
			config.replanning().addStrategySettings(strategySettings);
			config.controller().setLastIteration(0);
			config.routing().setNetworkModes(railwayModes);
			config.qsim().setMainModes(railwayModes);
			controller.run();
		}

		// nur Deutschland betrachten

		System.out.println("sum, average, sum*tons and average weighted by tons");
		System.out.println("trips,tons,length_access_km,length_egress_km,length_non_electrified_km,length_electrified_km,length_electrified_incl_proposed_km");
		System.out.println(counterRailTrips + "," + sumTons + "," + accessLegLengthNonElectrifiedKm + "," + egressLegLengthNonElectrifiedKm + "," +
			sumLengthNonElectrifiedKm + "," + sumLengthElectrifiedKm + "," + sumLengthElectrifiedOrProposedKm);
		System.out.println(counterRailTrips/counterRailTrips + "," + sumTons/counterRailTrips + "," + accessLegLengthNonElectrifiedKm/counterRailTrips + "," +
			egressLegLengthNonElectrifiedKm/counterRailTrips + "," + sumLengthNonElectrifiedKm/counterRailTrips + "," +
			sumLengthElectrifiedKm/counterRailTrips + "," + sumLengthElectrifiedOrProposedKm/counterRailTrips);
		System.out.println(counterRailTrips + "," + 1.0 + "," + accessLegLengthNonElectrifiedWeighted + "," +
			egressLegLengthNonElectrifiedWeighted + "," + sumLengthNonElectrifiedWeighted + "," +
			sumLengthElectrifiedWeighted + "," + sumLengthElectrifiedOrProposedWeighted);
		System.out.println(counterRailTrips/sumTons + "," + 1.0 + "," + accessLegLengthNonElectrifiedWeighted/sumTons + "," +
			egressLegLengthNonElectrifiedWeighted/sumTons + "," + sumLengthNonElectrifiedWeighted/sumTons + "," +
			sumLengthElectrifiedWeighted/sumTons + "," + sumLengthElectrifiedOrProposedWeighted/sumTons);

		// additionally weighted by tons

		// 1pct or 5pct schlechteste raussuchen (access/egress schlecht, Umweg gross)

		// Mail an Goehlichs zu Ladezeiten, Kosten Batterielok in Google Docs vorschreiben, ggf. RE fragen, Wir versuchen unseren Teil mit Verspätung noch fertigzustellen. Im großen , zum Vergleich würde uns interessieren wie teuer so eine Lok wäre. Habt ihr da eine Intuition


		// route : all, only electrified, proposed-electrified
		// output: from, to, tons/year, length non-electrified, electrified, proposed-electrified
		// ggf. Streckenbelastungen
		return 0;
	}

	private static boolean cellInGermany(String cell) {
		int originCellMainRun = Integer.parseInt(cell);
		if (originCellMainRun < 100000) {
			// case 5-digit Verkehrsprognose 2030 cells
			return originCellMainRun < 21000;
		} else {
			// case 7-digit Verkehrsprognose 2030 cells
			return originCellMainRun < 2100000;
		}
	}
}
