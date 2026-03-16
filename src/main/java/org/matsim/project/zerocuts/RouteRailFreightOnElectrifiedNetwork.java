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
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.network.filter.NetworkFilterManager;
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

import static org.matsim.prepare.longDistanceFreightGER.GenerateFreightPlans.*;

public class RouteRailFreightOnElectrifiedNetwork implements MATSimAppCommand {

	@CommandLine.Option(names = "--input-network", description = "input network", required = true,
//		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-network.xml.gz")
	defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/network-railways-final.xml.gz")

	private Path inputNetwork;

	@CommandLine.Option(names = "--input-freight-plans", description = "input freight plans", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/german-wide-freight-v3/before-calibration/german-wide-freight-v3-1.0pct.plans.xml.gz")
	private Path inputFreightPlans;

	@CommandLine.Option(names = "--output", description = "output csv file", required = true,
		defaultValue = "../shared-svn/projects/matsim-germany/zerocuts2/freight-rail_routes-on-electrified-network-analysis.csv")
	private Path output;

	private com.google.inject.Injector injector;
	private TripRouter tripRouter;
	private String columnSeparator = ",";
	private static final Logger log = LogManager.getLogger(RouteRailFreightOnElectrifiedNetwork.class);

	static final String[] HEADER = {"fromX", "fromY", "toX", "toY", "fromLink", "toLink",
		"pre-run_mode", "main-run_mode", "post-run_mode", "initial_origin_cell", "origin_cell_main_run", "destination_cell_main_run",
		"final_destination_cell", "goods_type", "tons_year", "length_access", "length_egress",
		"length_non_electrified", "length_electrified", "length_electrified_incl_proposed"};

	public static void main(String[] args) {
		new RouteRailFreightOnElectrifiedNetwork().execute(args);
	}

	public Integer call() throws Exception {
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
		ScoringConfigGroup.ModeParams scoreFreightRail = new ScoringConfigGroup.ModeParams(LEG_MODE_FREIGHT_RAIL);
		ScoringConfigGroup.ModeParams scoreRailElectrified = new ScoringConfigGroup.ModeParams(CreateNetworkFromOSM.RAIL_ELECTRIFIED);
		ScoringConfigGroup.ModeParams scoreRailElectrifiedInclProposed = new ScoringConfigGroup.ModeParams(CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED);

		config.scoring().addModeParams(scoreFreightRail);
		config.scoring().addModeParams(scoreRailElectrified);
		config.scoring().addModeParams(scoreRailElectrifiedInclProposed);

		Scenario scenario = ScenarioUtils.createScenario(config);
		Set<String> modesThatNeedVehicleTypes = new HashSet<>();
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_ROAD);
		modesThatNeedVehicleTypes.add(LEG_MODE_FREIGHT_RAIL);
		modesThatNeedVehicleTypes.addAll(railwayModes);
		Map<String, Id<VehicleType>> modeToVehicleType = new HashMap<>();
		for (String mode: modesThatNeedVehicleTypes) {
			VehicleType vehicleType = scenario.getVehicles().addModeVehicleType(mode);
			vehicleType.setMaximumVelocity(100/3.6);
			modeToVehicleType.put(mode, vehicleType.getId());
		}
		ScenarioUtils.loadScenario(scenario);

		// Since this is not running PersonPrepareForSim, we have to manually add all VehicleTypes to all Persons (it failed without), and even then
		// it failed with "Could not retrieve vehicle id from person: longDistanceFreight_0_0_main", so explicitly add Vehicles...
		for (Person person: scenario.getPopulation().getPersons().values()) {
			VehicleUtils.insertVehicleTypesIntoPersonAttributes(person, modeToVehicleType);
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
		int counter = 0;
		int nextMsg=1;
		for (Person person: scenario.getPopulation().getPersons().values()) {
			counter++;
			if (counter % nextMsg == 0) {
				nextMsg *= 4;
				log.info(" person # " + counter);}
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

			Facility originFacility = FacilitiesUtils.wrapActivity(trip.getOriginActivity());
			Facility destinationFacility = FacilitiesUtils.wrapActivity(trip.getDestinationActivity());
			List<PlanElement> routeNonElectrified = (List<PlanElement>) tripRouter.calcRoute(
				OsmTags.RAIL, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
			Leg accessLeg = (Leg) routeNonElectrified.get(0);
			Leg egressLeg = (Leg) routeNonElectrified.get(4);
			Leg nonElectrifiedLeg = (Leg) routeNonElectrified.get(2);
			List<PlanElement> routeElectrified = (List<PlanElement>) tripRouter.calcRoute(
				CreateNetworkFromOSM.RAIL_ELECTRIFIED, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
			Leg electrifiedLeg = (Leg) routeElectrified.get(2);
			List<PlanElement> routeElectrifiedInclProposed = (List<PlanElement>) tripRouter.calcRoute(
				CreateNetworkFromOSM.RAIL_ELECTRIFIED_INCL_PROPOSED, originFacility, destinationFacility, 0.0d, person, trip.getTripAttributes());
			Leg electrifiedInclProposedLeg = (Leg) routeElectrifiedInclProposed.get(2);

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
				printer.print(person.getAttributes().getAttribute("goods_type"));
				printer.print(person.getAttributes().getAttribute("tons_per_year"));
				printer.print(accessLeg.getRoute().getDistance());
				printer.print(egressLeg.getRoute().getDistance());
				printer.print(nonElectrifiedLeg.getRoute().getDistance());
				printer.print(electrifiedLeg.getRoute().getDistance());
				printer.print(electrifiedInclProposedLeg.getRoute().getDistance());
				printer.println();
			} catch (IOException e) {
				log.error(e);
			}
		}

		printer.flush();
		printer.close();

		// route : all, only electrified, proposed-electrified
		// output: from, to, tons/year, length non-electrified, electrified, proposed-electrified
		// ggf. Streckenbelastungen
		return 0;
	}
}
