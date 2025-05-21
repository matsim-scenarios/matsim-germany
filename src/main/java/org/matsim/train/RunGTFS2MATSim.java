/* *********************************************************************** *
 * project: org.matsim.*
 * EditRoutesTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2019 by the members listed in the COPYING,        *
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


package org.matsim.train;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.TransitScheduleWriterV2;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.vehicles.*;

/**
 * @author smueller
 */

public class RunGTFS2MATSim {

	private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(RunGTFS2MATSim.class);
	private static final String svnDir = "../";
	//	private static final String DBGTFSFile = svnDir + "public-svn/matsim/scenarios/countries/de/germany/original_data/gtfs/2019.zip";
//	private static final String DBGTFSFile = svnDir + "public-svn/matsim/scenarios/countries/de/germany/original_data/gtfs/2016.zip";
	private static final String FernGTFSFile = svnDir + "public-svn/matsim/scenarios/countries/de/germany/original_data/gtfs.de/Fernverkehr.zip";
	private static final String RegioGTFSFile = svnDir + "public-svn/matsim/scenarios/countries/de/germany/original_data/gtfs.de/Regionalverkehr.zip";
	private static final String NahGTFSFile = svnDir + "public-svn/matsim/scenarios/countries/de/germany/original_data/gtfs.de/Nahverkehr_corr.zip";


	public static void main(String[] args) {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
//		Scenario DBScenario = new CreatePtScheduleAndVehiclesFromGtfs().run(DBGTFSFile, "2019-11-23", "DB_");
//		Scenario DBScenario = new CreatePtScheduleAndVehiclesFromGtfs().run(DBGTFSFile, "2016-11-24", "DB_");

		Scenario FernScenario = new CreatePtScheduleAndVehiclesFromGtfs().run(FernGTFSFile, "2020-02-11", "Fern_");
		mergeSchedules("Fern_", scenario.getTransitSchedule().getFactory(), scenario.getTransitSchedule(), FernScenario.getTransitSchedule(), "longDistanceTrain");
		mergeVehicles("Fern_", scenario.getTransitVehicles().getFactory(), scenario.getTransitVehicles(), FernScenario.getTransitVehicles());

		Scenario RegioScenario = new CreatePtScheduleAndVehiclesFromGtfs().run(RegioGTFSFile, "2020-02-11", "Regio_");
		mergeSchedules("Regio_", scenario.getTransitSchedule().getFactory(), scenario.getTransitSchedule(), RegioScenario.getTransitSchedule(), "regionalTrain");
		mergeVehicles("Regio_", scenario.getTransitVehicles().getFactory(), scenario.getTransitVehicles(), RegioScenario.getTransitVehicles());

		Scenario NahScenario = new CreatePtScheduleAndVehiclesFromGtfs().run(NahGTFSFile, "2020-02-11", "Nah_");
//		getTsWithLinesWithMoreThanXxDep is used to reduce the size of the transit schedule.
//		It is set so the transit schedule only contains lines with more than ten departures  which reduces the number of lines by approx. 30%.
//		This reduces computing time during the simulation by approx. 50%
		mergeSchedules("Nah_", scenario.getTransitSchedule().getFactory(), scenario.getTransitSchedule(), getTsWithLinesWithMoreThanXxDep(NahScenario.getTransitSchedule(), 10), "localPublicTransport");
		mergeVehicles("Nah_", scenario.getTransitVehicles().getFactory(), scenario.getTransitVehicles(), NahScenario.getTransitVehicles());

		new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), "Train_").createNetwork();

//		sets link speeds to the maximum speed of all train trips that travel on this link
//		this should insure, that no trips are late, some may, however, be early
		setLinkSpeedsToMax(scenario);

//		sets link speeds to an average speed of all train trips that travel on this link
//		setLinkSpeedsToAverage(scenario);

//		check for links with length zero and set lenghts of these links to 50
		checkNetworkForLinksWithLenght0(scenario);

		new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(svnDir + "public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitVehicles_cleaned.xml.gz");
		new TransitScheduleWriterV2(scenario.getTransitSchedule()).write(svnDir + "public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitSchedule_cleaned.xml.gz");
		new NetworkWriter(scenario.getNetwork()).write(svnDir + "public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_network_cleaned.xml.gz");

//		run to check scenario
//		runScenario(scenario);

	}

	static void runScenario(Scenario scenario) {

		Config config = scenario.getConfig();

		config.controller().setOutputDirectory("output/");
		config.controller().setLastIteration(0);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		config.global().setNumberOfThreads(8);
		config.controller().setRunId("test_GTFS_Schedule");
//		config.qsim().setEndTime(48*3600);

		config.transit().setUseTransit(true);

//		set to a a low value to increase performance
		config.transitRouter().setMaxBeelineWalkConnectionDistance(1);

		Controler controler = new Controler(scenario);

		controler.run();

	}

	private static void setLinkSpeedsToAverage(Scenario scenario) {
		Map<Id<Link>, Double> linkSpeedSums = new HashMap<>();
		Map<Id<Link>, Double> linkSpeedNumbers = new HashMap<>();

		for (Link link : scenario.getNetwork().getLinks().values()) {
			linkSpeedSums.put(link.getId(), 0.);
			linkSpeedNumbers.put(link.getId(), 0.);
		}


		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			for (TransitRoute transitRoute : line.getRoutes().values()) {
				double arrivalTime = 0;
				double departureTime = 0;
				for (int ii = 0; ii < transitRoute.getStops().size(); ii++) {

					arrivalTime = transitRoute.getStops().get(ii).getArrivalOffset().seconds();
					Id<Link> linkId = null;
					if (ii == 0) {
						linkId = transitRoute.getRoute().getStartLinkId();
						linkSpeedSums.replace(linkId, 50.);
						linkSpeedNumbers.replace(linkId, 1.);

					} else {

						if (ii == transitRoute.getStops().size() - 1) {
							linkId = transitRoute.getRoute().getEndLinkId();
						} else {
							linkId = transitRoute.getRoute().getLinkIds().get(ii - 1);
						}

						Double speedSum = linkSpeedSums.get(linkId);
						speedSum += scenario.getNetwork().getLinks().get(linkId).getLength() / (arrivalTime - departureTime);
						linkSpeedSums.replace(linkId, speedSum);

						Double speedNumber = linkSpeedNumbers.get(linkId);
						speedNumber += 1;
						linkSpeedNumbers.replace(linkId, speedNumber);


					}

					departureTime = transitRoute.getStops().get(ii).getDepartureOffset().seconds();
				}


			}

		}

		for (Link link : scenario.getNetwork().getLinks().values()) {
			double speed = linkSpeedSums.get(link.getId()) / linkSpeedNumbers.get(link.getId());
			link.setFreespeed(speed);
			if (speed > 200. / 3.6) {
				log.warn("Link speed is higher than 200 km/h on link " + link.getId() + " - Speed is " + Math.round(speed * 3.6) + " km/h");
			}
			if (speed < 30. / 3.6) {
				log.warn("Link speed is lower than 30 km/h on link " + link.getId() + " - Speed is " + Math.round(speed * 3.6) + " km/h");
			}
		}

	}

	static void setLinkSpeedsToMax(Scenario scenario) {
		Map<Id<Link>, Double> linkMaxSpeed = new HashMap<>();


		for (Link link : scenario.getNetwork().getLinks().values()) {
			linkMaxSpeed.put(link.getId(), 0.);
		}


		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			for (TransitRoute transitRoute : line.getRoutes().values()) {
				double arrivalTime = 0;
				double departureTime = 0;
				for (int ii = 0; ii < transitRoute.getStops().size(); ii++) {

					arrivalTime = transitRoute.getStops().get(ii).getArrivalOffset().seconds();
					Id<Link> linkId = null;
					if (ii == 0) {
						linkId = transitRoute.getRoute().getStartLinkId();
						linkMaxSpeed.replace(linkId, 50.);
					} else {

						if (ii == transitRoute.getStops().size() - 1) {
							linkId = transitRoute.getRoute().getEndLinkId();
						} else {
							linkId = transitRoute.getRoute().getLinkIds().get(ii - 1);
						}

						Double prevSpeed = linkMaxSpeed.get(linkId);
						double newSpeed = 50.;
						if (arrivalTime - departureTime != 0) {
							newSpeed = scenario.getNetwork().getLinks().get(linkId).getLength() / (-1 + arrivalTime - departureTime);
						}

						if (newSpeed > prevSpeed) {
							linkMaxSpeed.replace(linkId, newSpeed);

						}

					}

					departureTime = transitRoute.getStops().get(ii).getDepartureOffset().seconds();
				}


			}

		}

		for (Link link : scenario.getNetwork().getLinks().values()) {
			double speed = linkMaxSpeed.get(link.getId());
			link.setFreespeed(speed);
			if (speed > 300. / 3.6) {
				log.warn("Link speed is higher than 300 km/h on link " + link.getId() + " - Speed is " + Math.round(speed * 3.6) + " km/h");
			}
			if (speed < 1. / 3.6) {
				log.warn("Link speed is lower than 1 km/h on link " + link.getId() + " - Speed is " + Math.round(speed * 3.6) + " km/h");
			}
		}

	}

	private static void mergeSchedules(String prefix, TransitScheduleFactory tsf, TransitSchedule schedule, TransitSchedule toBeMerged, String mode) {
		toBeMerged.getTransitLines().values().forEach(transitLine -> {
			TransitLine transitLineWithNewId = tsf.createTransitLine(Id.create(prefix + transitLine.getId().toString(), TransitLine.class));
			transitLine.getRoutes().values().forEach(route -> {
				List<TransitRouteStop> stops = new ArrayList<>();
				route.getStops().forEach(stop -> {
					TransitStopFacility transitStopWithNewId;
					if (!schedule.getFacilities().containsKey(Id.create(prefix + stop.getStopFacility().getId(), TransitStopFacility.class))) {
						transitStopWithNewId = tsf.createTransitStopFacility(Id.create(prefix + stop.getStopFacility().getId(), TransitStopFacility.class), stop.getStopFacility().getCoord(), stop.getStopFacility().getIsBlockingLane());
						transitStopWithNewId.setName(stop.getStopFacility().getName());
						transitStopWithNewId.getAttributes().putAttribute("type", mode);
						schedule.addStopFacility(transitStopWithNewId);
					} else {
						transitStopWithNewId = schedule.getFacilities().get(Id.create(prefix + stop.getStopFacility().getId(), TransitStopFacility.class));
					}
					TransitRouteStop transitRouteStopWithNewId = tsf.createTransitRouteStop(transitStopWithNewId, stop.getArrivalOffset(), stop.getDepartureOffset());
					transitRouteStopWithNewId.setAwaitDepartureTime(true);
					stops.add(transitRouteStopWithNewId);
				});
				TransitRoute transitRouteWithNewId = tsf.createTransitRoute(Id.create(prefix + route.getId().toString(), TransitRoute.class), route.getRoute(), stops, mode);
				transitLineWithNewId.addRoute(transitRouteWithNewId);
				route.getDepartures().values().forEach(dep -> {
					Departure departureWithNewId = tsf.createDeparture(Id.create(prefix + dep.getId().toString(), Departure.class), dep.getDepartureTime());
					departureWithNewId.setVehicleId(Id.createVehicleId(prefix + dep.getVehicleId().toString()));
					transitRouteWithNewId.addDeparture(departureWithNewId);
				});
			});
			transitLineWithNewId.setName(transitLine.getName());
			schedule.addTransitLine(transitLineWithNewId);
		});
	}

	private static void mergeVehicles(String prefix, VehiclesFactory vehiclesFactory, Vehicles vehicles, Vehicles toBeMerged) {

		toBeMerged.getVehicleTypes().values().forEach(vehicleType -> {
			VehicleType vehicleTypeWithNewId = vehiclesFactory.createVehicleType(Id.create(prefix + vehicleType.getId().toString(), VehicleType.class));
			vehicleTypeWithNewId.getCapacity().setSeats(1000);
			vehicles.addVehicleType(vehicleTypeWithNewId);
		});
		toBeMerged.getVehicles().values().forEach(vehicle -> {
			VehicleType vehicleTypeWithNewId = vehiclesFactory.createVehicleType(Id.create(prefix + vehicle.getType().getId().toString(), VehicleType.class));
			vehicleTypeWithNewId.getCapacity().setSeats(1000);
			Vehicle vehicleWithNewId = vehiclesFactory.createVehicle(Id.createVehicleId(prefix + vehicle.getId().toString()), vehicleTypeWithNewId);
			vehicles.addVehicle(vehicleWithNewId);
		});

	}

	private static void checkNetworkForLinksWithLenght0(Scenario scenario) {
		scenario.getNetwork().getLinks().values().forEach(link -> {
			if (link.getLength() == 0) {
				log.warn("Link length is 0. Setting to 50. Link: " + link.getId().toString());
				link.setLength(50.);
				link.setFreespeed(50.);
			}
		});
	}

	public static TransitSchedule getTsWithLinesWithMoreThanXxDep(TransitSchedule ts, int minNoOfDep) {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitSchedule newTs = scenario.getTransitSchedule();

		Map<Id<TransitLine>, Integer> map = new HashMap<Id<TransitLine>, Integer>();

		ts.getTransitLines().values().forEach(line -> {
			int noOfDepartures = 0;
			for (TransitRoute route : line.getRoutes().values()) {
				noOfDepartures += route.getDepartures().size();
			}
			map.put(line.getId(), noOfDepartures);
		});
		log.warn("No of lines in original schedule: " + ts.getTransitLines().size());
		int noOfLinesToBeDeleted = 0;
		for (int ii : map.values()) {
			if (ii <= minNoOfDep) {
				noOfLinesToBeDeleted++;
			}

		}

		log.warn("No of lines which don't have at least " + minNoOfDep + " departures: " + noOfLinesToBeDeleted);

		for (Id<TransitLine> lineId : map.keySet()) {
			if (map.get(lineId) > minNoOfDep) {
				TransitLine line = ts.getTransitLines().get(lineId);
				newTs.addTransitLine(line);
				for (TransitRoute route : line.getRoutes().values()) {
					for (TransitRouteStop stop : route.getStops()) {
						if (!newTs.getFacilities().containsKey(stop.getStopFacility().getId())) {
							newTs.addStopFacility(stop.getStopFacility());
						}
					}
				}
			}
		}

		log.warn("No of lines in new schedule: " + newTs.getTransitLines().size());
		return newTs;

	}


}
