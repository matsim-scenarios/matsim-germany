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
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.filter.NetworkFilterManager;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.prepare.CreateNetworkFromOSM;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.matsim.prepare.longDistanceFreightGER.GenerateFreightPlans.*;

public class RouteRailFreightOnElectrifiedNetwork implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-network", description = "input network", required = true,
	defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-network-railways-final.xml.gz")

	private Path inputNetwork;

	@CommandLine.Option(names = "--input-freight-plans", description = "input freight plans", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-1.0pct.plans.xml.gz")
	private Path inputFreightPlans;

	@CommandLine.Option(names = "--germanyShp", description = "germany boundaries shp for filtering.",
//		defaultValue = "../shared-svn/projects/matsim-germany/shp/germany-area.shp")
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/shp/germany-railway-borders.shp")
	private Path germanyShp;

	enum NetworkOutsideGermanyHandling {
		USE_AS_IS, USE_ELECTRIFIED_NETWORK_ONLY//, CUT_NON_ELECTRIFIED_BORDER_CROSSINGS
	}
	@CommandLine.Option(names = "--outsideGermanyHandling", description = "germany boundaries shp for filtering.",
		defaultValue = "USE_ELECTRIFIED_NETWORK_ONLY")
	private NetworkOutsideGermanyHandling networkOutsideGermanyHandling;

	@CommandLine.Option(names = "--outputDirectory", description = "output csv file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/railfreight_routes")
	private Path output;

	enum TypeOfTraffic {
		insideGermany, enteringGermany, exitingGermany, transitingGermany
	}

	private com.google.inject.Injector injector;
	private TripRouter tripRouter;
	private String columnSeparator = ",";
	private static final Logger log = LogManager.getLogger(RouteRailFreightOnElectrifiedNetwork.class);

	static final String[] HEADER = {"fromX", "fromY", "toX", "toY", "fromLink", "toLink",
		"pre-run_mode", "main-run_mode", "post-run_mode", "initial_origin_cell", "origin_cell_main_run", "destination_cell_main_run",
		"final_destination_cell", "origin_cell_main_run_in_germany", "destination_cell_main_run_in_germany", "goods_type", "tons_year",
		"person_id", "length_access_km", "length_egress_km",
		"length_non_electrified_km", "length_electrified_km", "length_electrified_incl_proposed_km",
		"length_non_electrified_km_Germany", "length_electrified_km_Germany", "length_electrified_incl_proposed_km_Germany"};

	public static void main(String[] args) {
		new RouteRailFreightOnElectrifiedNetwork().execute(args);
	}

	public Integer call() throws Exception {
		if(Files.notExists(output)) {Files.createDirectory(output);}

		Config config = ConfigUtils.createConfig();
		config.global().setCoordinateSystem("EPSG:25832");
		config.qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);

		// using default ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT we randomly get non-electrified routes longer than respective electrified routes (2 in a 304 relations sample)
//		config.controller().setRoutingAlgorithmType(ControllerConfigGroup.RoutingAlgorithmType.SpeedyALT);
		// In the same 304 relations sample (german-wide-freight-v3-0.1pct.plans.xml.gz) both Dijkstra and AStarLandmarks had 0 relations with non-electrified route longer than respective electrified route.
		// Of all 302717 relations, only 2 had that issue with AStarLandmarks (those are different relations from the 2 in the small SpeedyALT relations sample).
		// Only Dijkstra gave 0 such issues on all 302717 relations, but takes 2 days on the cluster.
		config.controller().setRoutingAlgorithmType(ControllerConfigGroup.RoutingAlgorithmType.Dijkstra);

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

		MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
		Set<String> modesThatNeedVehicleTypes = new HashSet<>();
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_ROAD);
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_RAIL);
		modesThatNeedVehicleTypes.addAll(railwayModes);

		ScenarioUtils.loadScenario(scenario);

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
			Map<String, Id<Vehicle>> modeToVehicle = new HashMap<>();
			for (Map.Entry<String, Id<VehicleType>> modeVehicleType: modeToVehicleType.entrySet()) {
				Id<Vehicle> vehicleId = VehicleUtils.createVehicleId(person, modeVehicleType.getKey());
				Vehicle vehicle = scenario.getVehicles().getFactory().createVehicle(vehicleId, scenario.getVehicles().getVehicleTypes().get(modeVehicleType.getValue()));
				scenario.getVehicles().addVehicle(vehicle);
				modeToVehicle.put(modeVehicleType.getKey(), vehicleId);
			}
			VehicleUtils.insertVehicleIdsIntoAttributes(person, modeToVehicle);
		}

		GeoFileReader germanyShapeReader = new GeoFileReader();
		Collection<SimpleFeature> germanyFeatures = germanyShapeReader.readFileAndInitialize(germanyShp.toString());
		List<Geometry> germanyGeometries;
		try {
			MathTransform transform = CRS.findMathTransform(germanyShapeReader.getCoordinateSystem(), CRS.decode(config.global().getCoordinateSystem()));
			germanyGeometries = germanyFeatures.stream().map(simpleFeature -> {
				try {
					return JTS.transform( (Geometry) simpleFeature.getDefaultGeometry(), transform);
				} catch (TransformException e) {
					throw new RuntimeException(e);
				}
			}).collect(Collectors.toList());
		} catch (FactoryException e) {
			throw new RuntimeException(e);
		}

		// tag all border crossing links
		List<Geometry> germanyBoundaries = germanyGeometries.stream().map(geom -> geom.getBoundary()).collect(Collectors.toList());
		Set<Id<Link>> borderCrossings = new HashSet<>();
		for (Link link: scenario.getNetwork().getLinks().values()) {
			boolean fromNodeInGermany = germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(link.getFromNode().getCoord())));
			boolean toNodeInGermany = germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(link.getToNode().getCoord())));
			if ((fromNodeInGermany & !toNodeInGermany) || (!fromNodeInGermany & toNodeInGermany)) {
				link.getAttributes().putAttribute("borderCrossing", "DE/*");
				borderCrossings.add(link.getId());
			}
		}

		if (networkOutsideGermanyHandling == NetworkOutsideGermanyHandling.USE_ELECTRIFIED_NETWORK_ONLY) {
			NetworkFilterManager filterGermanyAndElectrifiedEurope = new NetworkFilterManager(scenario.getNetwork(), new NetworkConfigGroup());
			filterGermanyAndElectrifiedEurope.addLinkFilter(l -> {
				if (l.getAllowedModes().contains(CreateNetworkFromOSM.RAIL_ELECTRIFIED)) {
					return true;
				} else if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getFromNode().getCoord()))) ||
					germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getToNode().getCoord())))) {
					return true;
				}
				return false;
			});
			Network railwayNetworkGermanyAndElectrifiedEurope = filterGermanyAndElectrifiedEurope.applyFilters();

			MultimodalNetworkCleaner networkCleaner = new MultimodalNetworkCleaner(railwayNetworkGermanyAndElectrifiedEurope);
			networkCleaner.run(Set.of(LEG_MODE_FREIGHT_RAIL));
			networkCleaner.run(Set.of(CreateNetworkFromOSM.RAIL_ELECTRIFIED));
			networkCleaner.run(Set.of(CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED));

			scenario.setNetwork(railwayNetworkGermanyAndElectrifiedEurope);
		}

		this.injector = new Injector.InjectorBuilder( scenario )
			.addStandardModules()
			.build();
		tripRouter = injector.getInstance( TripRouter.class );

		NetworkFilterManager filterElectrified = new NetworkFilterManager(scenario.getNetwork(), new NetworkConfigGroup());
		filterElectrified.addLinkFilter(l -> {
			if (l.getAllowedModes().contains(CreateNetworkFromOSM.RAIL_ELECTRIFIED)) {
				return true;
			}
			return false;
		});
		Network electrifiedRailwayNetwork = filterElectrified.applyFilters();

		NetworkFilterManager filterGermany = new NetworkFilterManager(scenario.getNetwork(), new NetworkConfigGroup());
		filterGermany.addLinkFilter(l -> {
			if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getFromNode().getCoord()))) ||
				germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getToNode().getCoord())))) {
				return true;
			}
			return false;
		});
		Network railwayNetworkGermany = filterGermany.applyFilters();

		NetworkFilterManager filterElectrifiedGermany = new NetworkFilterManager(electrifiedRailwayNetwork, new NetworkConfigGroup());
		filterElectrifiedGermany.addLinkFilter(l -> {
			if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getFromNode().getCoord()))) ||
				germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getToNode().getCoord())))) {
				return true;
			}
			return false;
		});
		Network electrifiedRailwayNetworkGermany = filterElectrifiedGermany.applyFilters();

		NetworkFilterManager filterElectrifiedEuropeOutsideGermany = new NetworkFilterManager(electrifiedRailwayNetwork, new NetworkConfigGroup());
		filterElectrifiedEuropeOutsideGermany.addLinkFilter(l -> {
			if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getFromNode().getCoord()))) ||
				germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(l.getToNode().getCoord())))) {
				return false;
			}
			return true;
		});
		Network electrifiedRailwayNetworkEuropeOutsideGermany = filterElectrifiedEuropeOutsideGermany.applyFilters();

		// test
		NetworkUtils.writeNetwork(scenario.getNetwork(), "../shared-svn/projects/matsim-germany/zerocuts2/railfreight_routes/network_border_attributes.xml.gz");
		NetworkUtils.writeNetwork(railwayNetworkGermany, "../shared-svn/projects/matsim-germany/zerocuts2/railfreight_routes/network_germany.xml.gz");


		CSVPrinter printer = null;
		try {printer = new CSVPrinter(IOUtils.getBufferedWriter(output.resolve("railfreight_electrified_route_analysis.csv").toString()),
			CSVFormat.Builder.create()
				.setDelimiter(columnSeparator)
				.setHeader(HEADER)
				.build());
		} catch (IOException e) {
			log.error(e);
		}

		// cache to speed up
		Map<Id<Link>, Map<Id<Link>, Relation>> cachedRelations = new HashMap<>();

		int counterAll = 0;
		int counterRailTrips = 0;
		int nextMsg=1;

		/*
		 * Route and analyze on 3 different filtered networks: all railways, already electrified, electrified incl. proposed electrification.
		 * This uses the pre-attributed and cleaned networks from CreateNetworkFromOSM.
		 * In order to make results comparable, map all activities to the nearest link on the already the electrified railway network.
		 * Track classes (maximum weight / axle) is not sufficiently tagged yet and therefore ignored. Also gradient is not sufficiently tagged
		 */
		for (Person person: scenario.getPopulation().getPersons().values()) {
			if (person.getId().toString().endsWith("_electrified") || person.getId().toString().endsWith("_electrifiedInclProposed")) { continue;}
			counterAll++;
			if (counterAll % nextMsg == 0) {
				nextMsg *= 4;
				log.info(" routed person # " + counterAll);}
			if (!person.getId().toString().contains(LONG_DISTANCE_FREIGHT)) {continue;}
			xy2link(person.getSelectedPlan(), scenario.getActivityFacilities(), electrifiedRailwayNetworkGermany,
				electrifiedRailwayNetworkEuropeOutsideGermany, germanyGeometries);
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

			// look up cached relations
			Map<Id<Link>, Relation> cachedRelationsFromLink = cachedRelations.get(originFacility.getLinkId());
			Relation relation = null;
			if (cachedRelationsFromLink == null) {
				// initialize map
				cachedRelations.put(originFacility.getLinkId(), new HashMap<>());
			} else {
				relation = cachedRelationsFromLink.get(destinationFacility.getLinkId());
			}
			if (relation == null) {
					// process relation
					relation = processRelation(originFacility, destinationFacility, person, trip, borderCrossings, scenario.getNetwork());
			}

			Person electrifiedRoutePerson = scenario.getPopulation().getPersons().get(Id.createPersonId(person.getId().toString() + "_electrified"));
			Person electrifiedInclProposedRoutePerson = scenario.getPopulation().getPersons().get(Id.createPersonId(person.getId().toString() + "_electrifiedInclProposed"));

			List<PlanElement> routeNonElectrified = relation.routeNonElectrified;
			Leg accessLeg = (Leg) routeNonElectrified.get(0);
			Leg egressLeg = (Leg) routeNonElectrified.get(4);
			Leg nonElectrifiedLeg = (Leg) routeNonElectrified.get(2);
			List<PlanElement> routeElectrified = relation.routeElectrified;
			Leg electrifiedLeg = (Leg) routeElectrified.get(2);
			List<PlanElement> routeElectrifiedInclProposed = relation.routeElectrifiedInclProposed;
			Leg electrifiedInclProposedLeg = (Leg) routeElectrifiedInclProposed.get(2);

			double tonsPerYear = (double) person.getAttributes().getAttribute("tons_per_year");

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
				printer.print(relation.typeOfTraffic == TypeOfTraffic.insideGermany || relation.typeOfTraffic == TypeOfTraffic.exitingGermany);
				printer.print(relation.typeOfTraffic == TypeOfTraffic.insideGermany || relation.typeOfTraffic == TypeOfTraffic.enteringGermany);
				printer.print(person.getAttributes().getAttribute("goods_type"));
				printer.print(person.getAttributes().getAttribute("tons_per_year"));
				printer.print(person.getId().toString());
				printer.print(accessLeg.getRoute().getDistance() / 1000);
				printer.print(egressLeg.getRoute().getDistance() / 1000);
				printer.print(nonElectrifiedLeg.getRoute().getDistance() / 1000);
				printer.print(electrifiedLeg.getRoute().getDistance() / 1000);
				printer.print(electrifiedInclProposedLeg.getRoute().getDistance() / 1000);
				printer.print(routeLengthInGermany(nonElectrifiedLeg, railwayNetworkGermany) / 1000);
				printer.print(routeLengthInGermany(electrifiedLeg, railwayNetworkGermany) / 1000);
				printer.print(routeLengthInGermany(electrifiedInclProposedLeg, railwayNetworkGermany) / 1000);
				printer.println();
			} catch (IOException e) {
				log.error(e);
			}
		}

		printer.flush();
		printer.close();

		PopulationWriter populationWriter = new PopulationWriter(scenario.getPopulation());
		populationWriter.write(output.resolve("railfreight_plans_routed.xml.gz").toString());

		// run mobism to have events for visualization in Via
		Controler controller = new Controler(scenario);
		config.controller().setOutputDirectory(output.resolve("run").toString());
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

	// adaptation of XY2Links from matsim-libs to ensure that German traffic is mapped to a link within Germany.
	private void xy2link(Plan plan, ActivityFacilities activityFacilities, Network networkGermany, Network networkEuropeOutsideGermany,
						 List<Geometry> germanyGeometries) {
		Objects.requireNonNull(activityFacilities);
		List<PlanElement> planElements = plan.getPlanElements();
		for (PlanElement planElement : planElements) {
			if (planElement instanceof Activity) {
				Activity act = (Activity) planElement;

				if (act.getLinkId() != null) {
					// there may be activities in a plan that have a link and others that have a coordinate.
					// Those that have a link do not need a new link.  In addition, they may not even have a
					// coordinate.  kai/dominik, nov'11
					continue;
				}

				if (act.getCoord() == null) {
					Gbl.assertNotNull(act.getFacilityId());
					final ActivityFacility activityFacility = activityFacilities.getFacilities().get(act.getFacilityId());
					Gbl.assertNotNull(activityFacility);
					act.setCoord(activityFacility.getCoord());
				}

				// If the linkId is still null get nearest link from the network
				Link link;
				if (germanyGeometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(act.getCoord())))) {
					link = NetworkUtils.getNearestLink(networkGermany, act.getCoord());
				} else {
					link = NetworkUtils.getNearestLink(networkEuropeOutsideGermany, act.getCoord());
				}

				if (null == link) {
					throw new RuntimeException("For person id=" + plan.getPerson().getId() + ": getNearestLink returned Null! act=" + act);
				}
				act.setLinkId(link.getId());
			}

		}
	}

	private double routeLengthInGermany(Leg leg, Network railwayNetworkGermany) {
		NetworkRoute networkRoute = (NetworkRoute) leg.getRoute();
		// similar to NetworkRoutingInclAccessEgressModule:451 return length 0 if start==end on network
		if (networkRoute.getStartLinkId().equals(networkRoute.getEndLinkId())) {
			return 0.0;
		}
		double length = 0.0;
		for (Id<Link> linkId : networkRoute.getLinkIds()) {
			length = length + getLinkLengthIfInGermany(railwayNetworkGermany, linkId);
		}
		// similar to NetworkRoutingInclAccessEgressModule:441 ignore length of the start link
		length = length + getLinkLengthIfInGermany(railwayNetworkGermany, networkRoute.getEndLinkId());
		return length;
	}

	private double getLinkLengthIfInGermany(Network railwayNetworkGermany, Id<Link> linkId) {
		Link link = railwayNetworkGermany.getLinks().get(linkId);
		// if link exists in filtered network it is located in Germany and we want to add its length
		if (link != null) {
			return link.getLength();
		} else {
			return 0.0;
		}
	}

	private double routeLength(NetworkRoute networkRoute, Network railwayNetworkGermany) {
		// similar to NetworkRoutingInclAccessEgressModule:451 return length 0 if start==end on network
		if (networkRoute.getStartLinkId().equals(networkRoute.getEndLinkId())) {
			return 0.0;
		}
		double length = 0.0;
		for (Id<Link> linkId : networkRoute.getLinkIds()) {
			length = length + railwayNetworkGermany.getLinks().get(linkId).getLength();
		}
		// similar to NetworkRoutingInclAccessEgressModule:441 ignore length of the start link
		length = length + railwayNetworkGermany.getLinks().get(networkRoute.getEndLinkId()).getLength();
		return length;
	}

	// TODO: try to ensure this uses no really Person-dependent data, only those saved in the Person but identical for the Relation
	private Relation processRelation (Facility originFacility, Facility destinationFacility, Person person, TripStructureUtils.Trip trip,
									  Set<Id<Link>> borderCrossings, Network network) {
		// mode railway occurs in input survey data only on main run
		boolean originCellMainRunInGermany = cellInGermany((String) person.getAttributes().getAttribute("origin_cell_main_run"));
		boolean destinationCellMainRunInGermany = cellInGermany((String) person.getAttributes().getAttribute("destination_cell_main_run"));


		// TODO: there can be electrified trips via foreign countries shorter than entirely inside Germany. E.g. Saarbruecken-Offenburg via France.
		// Therefore pick first entering into Germany and last exiting out of Germany and enforce routing inside Germany in between by filtering network.
		// This can be justified by the hurdles in crossing the border (locomotive certification and electricity supply, organisational)
		// Since Basel - Schaffhausen - Lindau is not fully electrified yet, maybe Basel Bad. is not a real problem. Maybe extend Germany shape to include all DB Netz/InfraGO areas.
		// OSM Tag operator is not sufficiently tagged. Big areas with "" inside Germany. Many different DB* attributes.

		TypeOfTraffic typeOfTraffic;
		if (originCellMainRunInGermany && destinationCellMainRunInGermany) {
			typeOfTraffic = TypeOfTraffic.insideGermany;
		} else if (!originCellMainRunInGermany && destinationCellMainRunInGermany) {
			typeOfTraffic = TypeOfTraffic.enteringGermany;
		} else if (originCellMainRunInGermany && !destinationCellMainRunInGermany) {
			typeOfTraffic = TypeOfTraffic.exitingGermany;
		} else {
			typeOfTraffic = TypeOfTraffic.transitingGermany;
		}

		Id<Link> originOrBorderToGermanyLink = originFacility.getLinkId();
		Id<Link> destinationOrBorderFromGermanyLink = destinationFacility.getLinkId();
		Facility originOrBorderToGermanyFacility = originFacility;
		Facility destinationOrBorderFromGermanyFacility = destinationFacility;
		// save subroute outside Germany
		NetworkRoute routeEnteringGermany = null;
		NetworkRoute routeExitingGermany = null;
		List<PlanElement> tripCrossBorder = null;

		// cut traffic at border
		if (!originCellMainRunInGermany || !destinationCellMainRunInGermany) {
			tripCrossBorder = (List<PlanElement>) tripRouter.calcRoute(CreateNetworkFromOSM.RAIL_ELECTRIFIED, originFacility,
				destinationFacility, 0.0d, person, trip.getTripAttributes());

			NetworkRoute crossBorderRoute = (NetworkRoute) ((Leg) tripCrossBorder.get(2)).getRoute();
			List<Id<Link>> borderCrossingsInRoute = crossBorderRoute.getLinkIds().stream().
				filter(link -> borderCrossings.contains(link)).toList();
			switch (typeOfTraffic) {
				case enteringGermany :
//					Verify.verify(borderCrossingsInRoute.size() == 1);
					if (borderCrossingsInRoute.size() > 1) log.warn("borderCrossings:" + borderCrossingsInRoute.size() + " instead of 1");
					originOrBorderToGermanyLink = borderCrossingsInRoute.getFirst();
					originOrBorderToGermanyFacility = FacilitiesUtils.wrapLink(network.getLinks().get(originOrBorderToGermanyLink));
					routeEnteringGermany = crossBorderRoute.getSubRoute(originFacility.getLinkId(), originOrBorderToGermanyLink);
					break;
				case exitingGermany :
//					Verify.verify(borderCrossingsInRoute.size() == 1);
					if (borderCrossingsInRoute.size() > 1) log.warn("borderCrossings:" + borderCrossingsInRoute.size() + " instead of 1");
					destinationOrBorderFromGermanyLink = borderCrossingsInRoute.getFirst();
					destinationOrBorderFromGermanyFacility = FacilitiesUtils.wrapLink(network.getLinks().get(destinationOrBorderFromGermanyLink));
					routeExitingGermany = crossBorderRoute.getSubRoute(destinationOrBorderFromGermanyLink, destinationFacility.getLinkId());
					break;
				case transitingGermany :
//					Verify.verify(borderCrossingsInRoute.size() == 2);
					if (borderCrossingsInRoute.size() != 2) {
						log.warn("borderCrossings:" + borderCrossingsInRoute.size() + " instead of 2 for person " + person.getId().toString() +
							" from link " + originFacility.getLinkId() + " to link " + destinationFacility.getLinkId() +
							" via border crossing links" +
							borderCrossingsInRoute.stream().map(id -> id.toString()).collect(Collectors.joining(",")));
					}
					originOrBorderToGermanyLink = borderCrossingsInRoute.getFirst();
					originOrBorderToGermanyFacility = FacilitiesUtils.wrapLink(network.getLinks().get(originOrBorderToGermanyLink));
					destinationOrBorderFromGermanyLink = borderCrossingsInRoute.getLast();
					destinationOrBorderFromGermanyFacility = FacilitiesUtils.wrapLink(network.getLinks().get(destinationOrBorderFromGermanyLink));
					routeEnteringGermany = crossBorderRoute.getSubRoute(originFacility.getLinkId(), originOrBorderToGermanyLink);
					routeExitingGermany = crossBorderRoute.getSubRoute(destinationOrBorderFromGermanyLink, destinationFacility.getLinkId());
					break;
				default: new RuntimeException("Should not happen.");
			}
		}



		List<PlanElement> routeNonElectrified = (List<PlanElement>) tripRouter.calcRoute(
			OsmTags.RAIL, originOrBorderToGermanyFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
		Leg nonElectrifiedLeg = (Leg) routeNonElectrified.get(2);
		List<PlanElement> routeElectrified = (List<PlanElement>) tripRouter.calcRoute(
			CreateNetworkFromOSM.RAIL_ELECTRIFIED, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
		Leg electrifiedLeg = (Leg) routeElectrified.get(2);
		List<PlanElement> routeElectrifiedInclProposed = (List<PlanElement>) tripRouter.calcRoute(
			CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
		Leg electrifiedInclProposedLeg = (Leg) routeElectrifiedInclProposed.get(2);


		// add route parts outside Germany
		if (!originCellMainRunInGermany || !destinationCellMainRunInGermany) {
			List<List<PlanElement>> tripsToBeExtendedBeyondBorder = List.of(routeNonElectrified, routeElectrified, routeElectrifiedInclProposed);
			for (List<PlanElement> tripToBeExtendedBeyondBorder : tripsToBeExtendedBeyondBorder) {
				List<Id<Link>> mergedLinkIds = new ArrayList<>();
				// Routing from a link causes the trip to start without any access walk and instead directly with the railway leg
				// So no need to remove a walk leg and interaction activity
				if (routeEnteringGermany != null) {
					int positionRailwayLeg = -1;
					if (tripToBeExtendedBeyondBorder.size() == 5) {
						positionRailwayLeg = 0;
						tripToBeExtendedBeyondBorder.remove(0); // remove walk leg at border
						tripToBeExtendedBeyondBorder.remove(0); // remove interaction activity at border
					} else if (tripToBeExtendedBeyondBorder.size() == 3) {
						positionRailwayLeg = 0;
					} else {
						new RuntimeException("unexpected number of PlanElements received from router. Aborting.");
					}
					// merge NetworkRoute
					Leg legToBeExtendedBeyondBorder = (Leg) tripToBeExtendedBeyondBorder.get(positionRailwayLeg);
					Leg legCrossBorder = (Leg) tripCrossBorder.get(2);
					NetworkRoute routeToBeExtendedBeyondBorder = (NetworkRoute) legToBeExtendedBeyondBorder.getRoute();
					NetworkRoute routeOutsideBorder = ((NetworkRoute) legCrossBorder.getRoute()).
						getSubRoute(originFacility.getLinkId(), originOrBorderToGermanyLink);
					mergedLinkIds.addAll(routeOutsideBorder.getLinkIds());
					// getLinkIds() excludes the start and end link, so we need to re-add
					mergedLinkIds.add(originOrBorderToGermanyLink);
					mergedLinkIds.addAll(routeToBeExtendedBeyondBorder.getLinkIds());

					tripToBeExtendedBeyondBorder.add(0, tripCrossBorder.get(0)); // add walk leg outside Germany
					tripToBeExtendedBeyondBorder.add(1, tripCrossBorder.get(1)); // add interaction activity outside Germany
				}
				if (routeExitingGermany != null) {
					if (tripToBeExtendedBeyondBorder.size() == 5) {
						tripToBeExtendedBeyondBorder.remove(4); // remove walk leg at border
						tripToBeExtendedBeyondBorder.remove(3); // remove interaction activity at border
					} else if (tripToBeExtendedBeyondBorder.size() != 3) {
						// Routing to a link can cause the trip to end without any egress walk and instead directly with the railway leg
						// So no need to remove a walk leg and interaction activity
						// But that means 3 PlanElements and here we have something else.
						new RuntimeException("unexpected number of PlanElements received from router. Aborting.");
					}
					// merge NetworkRoute
					Leg legToBeExtendedBeyondBorder = (Leg) tripToBeExtendedBeyondBorder.get(2);
					Leg legCrossBorder = (Leg) tripCrossBorder.get(2);
					NetworkRoute routeToBeExtendedBeyondBorder = (NetworkRoute) legToBeExtendedBeyondBorder.getRoute();
					NetworkRoute routeOutsideBorder = ((NetworkRoute) legCrossBorder.getRoute()).
						getSubRoute(destinationOrBorderFromGermanyLink, destinationFacility.getLinkId());
					mergedLinkIds.addAll(routeOutsideBorder.getLinkIds());
					// getLinkIds() excludes the start and end link, so we need to re-add
					mergedLinkIds.add(originOrBorderToGermanyLink);
					mergedLinkIds.addAll(routeToBeExtendedBeyondBorder.getLinkIds());

					tripToBeExtendedBeyondBorder.add(3, tripCrossBorder.get(3)); // add interaction activity outside Germany
					tripToBeExtendedBeyondBorder.add(4, tripCrossBorder.get(4)); // add walk leg outside Germany
				}
				// LinkNetworkRouteImpl.getSubRoute() does not set distance, travel time and cost. So we have to re-calculate
				NetworkRoute mergedRoute = RouteUtils.createLinkNetworkRouteImpl(originFacility.getLinkId(), destinationFacility.getLinkId());
				mergedRoute.setLinkIds(originFacility.getLinkId(), mergedLinkIds, destinationFacility.getLinkId());
				mergedRoute.setDistance(routeLength(mergedRoute, network));
			}
		}

		return new Relation(originFacility.getLinkId(), destinationFacility.getLinkId(), typeOfTraffic, originOrBorderToGermanyLink,
			destinationOrBorderFromGermanyLink, routeNonElectrified, routeElectrified, routeElectrifiedInclProposed);
	}

	// We cannot use Facility as identifier, so instead use Id<Link> of the facility's link
	private record Relation (Id<Link> originOfRelation, Id<Link> destinationOfRelation, TypeOfTraffic typeOfTraffic,
							 Id<Link> originOrBorderLinkToGermany, Id<Link> desrinationOrBorderLinkFromGermany,
							 List<PlanElement> routeNonElectrified, List<PlanElement> routeElectrified,
							 List<PlanElement> routeElectrifiedInclProposed) {
	}
}
