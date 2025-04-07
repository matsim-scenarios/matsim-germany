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


package org.matsim.run;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup.TrafficDynamics;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.pt.transitSchedule.TransitScheduleUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicles;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.ModeMappingForPassengersParameterSet;
import ch.sbb.matsim.routing.pt.raptor.RaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;

import org.matsim.contrib.accessibility.utils.MergeNetworks;


/**
* @author smueller
*/

public class RunGermany {
	
//	private static final String inputDir ="../shared-svn/studies/countries/de/matsim-germany/matsim-input/";
//	private static final String outputDir = "../shared-svn/studies/countries/de/matsim-germany/matsim-output/";

//	contains all primary, trunk and motorway roads from osm
//	private static final String inputNetworkRoads = 		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/network_osm_primary.xml.gz";
	private static final String inputNetworkRoads = 		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/network_osm_secondary.xml.gz";

	//	contains all db ice and ic services from 2016 from gtfs data
//	private static final String inputNetworkTrain =		 	 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2016_DB_GTFS_network.xml.gz";
//	private static final String inputScheduleTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2016_DB_GTFS_transitSchedule.xml.gz";
//	private static final String inputVehiclesTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2016_DB_GTFS_transitVehicles.xml.gz";
	
	//	contains all db ice and ic services from 2019 from gtfs data
//	private static final String inputNetworkTrain =		 	 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2019_DB_GTFS_network.xml.gz";
//	private static final String inputScheduleTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2019_DB_GTFS_transitSchedule.xml.gz";
//	private static final String inputVehiclesTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2019_DB_GTFS_transitVehicles.xml.gz";
	
//	contains 2020 train data from gtfs.de --> update gtfs
	private static final String inputNetworkTrain =		 	 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_network_cleaned.xml.gz";
	private static final String inputScheduleTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitSchedule_cleaned.xml.gz";
	private static final String inputVehiclesTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitVehicles_cleaned.xml.gz";
	
//	private static final String inputNetworkTrain =		 	 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_network_noLocal.xml.gz";
//	private static final String inputScheduleTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitSchedule_noLocal.xml.gz";
//	private static final String inputVehiclesTrain =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2020_Train_GTFS_transitVehicles_noLocal.xml.gz";
	
//	private static final String inputNetworkTrain_2030 =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2030_network.xml.gz";
//	private static final String inputScheduleTrain_2030 =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2030_transitSchedule.xml.gz";
//	private static final String inputVehiclesTrain_2030 =		 "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/germany/input/2030_transitVehicles.xml.gz";

//	contains all german plane services from 09/09 from oag data
	private static final String inputNetworkPlane = 		"network_plane.xml";
	private static final String inputSchedulePlane =		"transit_schedule_plane.xml";
	private static final String inputVehiclesPlane =		"transit_vehicles_plane.xml";

	private static final String inputPlans =				"germany_0.01pct.xml";
	
	private static final String longDistanceTrain = 		"longDistanceTrain";
	private static final String regionalTrain = 			"regionalTrain";
	private static final String localPublicTransport = 		"localPublicTransport";

	private static final int noOfThreads = 8;
	
//	private static final String runid = "ChangeTripMode_Secondary";

	public static void main(String[] args) {
		
		String inputDir = args[0];
		String[] typedArgs = Arrays.copyOfRange( args, 1, args.length );
		Config config = ConfigUtils.loadConfig(inputDir + "germanyConfig.xml");
		
		config.controller().setLastIteration(100);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.failIfDirectoryExists);
		config.controller().setOutputDirectory(inputDir + "/Output");
//		config.controler().setRunId(runid);

		config.global().setCoordinateSystem("EPSG:31467");
		config.global().setNumberOfThreads(noOfThreads);
				
		Collection<String> networkModes = new HashSet<>();
		networkModes.add("car");
		config.routing().setNetworkModes(networkModes);
		
//		we are only scoring trips
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("origin").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("shop").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("business").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("holiday").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("education").setScoringThisActivityAtAll(false));
		config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("other").setScoringThisActivityAtAll(false));
				
		config.qsim().setStartTime(0);
		config.qsim().setEndTime(36. * 3600);
		config.qsim().setNumberOfThreads(noOfThreads);
		config.qsim().setMainModes(networkModes);
		
		// vsp defaults
		config.vspExperimental().setVspDefaultsCheckingLevel( VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info );
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation( true );
		config.qsim().setTrafficDynamics( TrafficDynamics.kinematicWaves );
		
		config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
		config.replanning().setMaxAgentPlanMemorySize(10);
		config.replanning().clearStrategySettings();

		ReplanningConfigGroup.StrategySettings stratSetsReRoute = new ReplanningConfigGroup.StrategySettings();
		stratSetsReRoute.setStrategyName(DefaultStrategy.ReRoute);
		stratSetsReRoute.setWeight(0.05);
		
//		StrategySettings stratSetsTimeAllocation = new StrategySettings();
//		stratSetsTimeAllocation.setStrategyName(DefaultStrategy.TimeAllocationMutator_ReRoute);
//		stratSetsTimeAllocation.setWeight(0.05);
//		config.timeAllocationMutator().setMutationRange(600.);

		ReplanningConfigGroup.StrategySettings stratSetsChangeTripMode = new ReplanningConfigGroup.StrategySettings();
		stratSetsChangeTripMode.setStrategyName(DefaultStrategy.ChangeTripMode);
		stratSetsChangeTripMode.setWeight(0.05);
		
		String[] changeModes = new String[2];
		changeModes[0] = "car";
		changeModes[1] = "longDistancePt";
		config.changeMode().setModes(changeModes);
		
		StrategySettings stratSetsChangeExpBeta = new StrategySettings();
		stratSetsChangeExpBeta.setStrategyName(DefaultSelector.ChangeExpBeta);
		stratSetsChangeExpBeta.setWeight(0.9);
		
		config.replanning().addStrategySettings(stratSetsReRoute);
//		config.replanning().addStrategySettings(stratSetsTimeAllocation);
		config.replanning().addStrategySettings(stratSetsChangeExpBeta);
		config.replanning().addStrategySettings(stratSetsChangeTripMode);
		
		config.network().setInputFile(inputNetworkRoads);
		
		config.plans().setInputFile(inputPlans);
		
		config.transit().setUseTransit(true);
		config.transitRouter().setMaxBeelineWalkConnectionDistance(500);
		
		Set<String> transitModes = new HashSet<>();
//		transitModes.add(TransportMode.train);
//		transitModes.add(TransportMode.airplane);
		transitModes.add("longDistancePt");
		config.transit().setTransitModes(transitModes );

		SwissRailRaptorConfigGroup srrConfig = new SwissRailRaptorConfigGroup();
		srrConfig.setUseModeMappingForPassengers(true);
		
//		ModeMappingForPassengersParameterSet modeMappingTrain = new ModeMappingForPassengersParameterSet();
//		modeMappingTrain.setPassengerMode(TransportMode.train);
//		modeMappingTrain.setRouteMode(TransportMode.train);
//		srrConfig.addModeMappingForPassengers(modeMappingTrain);
		
		ModeMappingForPassengersParameterSet modeMappingLongDistanceTrain = new ModeMappingForPassengersParameterSet();
		modeMappingLongDistanceTrain.setPassengerMode(longDistanceTrain);
		modeMappingLongDistanceTrain.setRouteMode(longDistanceTrain);
		srrConfig.addModeMappingForPassengers(modeMappingLongDistanceTrain);
		
		ModeMappingForPassengersParameterSet modeMappingRegionalTrain = new ModeMappingForPassengersParameterSet();
		modeMappingRegionalTrain.setPassengerMode(regionalTrain);
		modeMappingRegionalTrain.setRouteMode(regionalTrain);
		srrConfig.addModeMappingForPassengers(modeMappingRegionalTrain);
		
		ModeMappingForPassengersParameterSet modeMappingTrainLocalPublicTransport = new ModeMappingForPassengersParameterSet();
		modeMappingTrainLocalPublicTransport.setPassengerMode(localPublicTransport);
		modeMappingTrainLocalPublicTransport.setRouteMode(localPublicTransport);
		srrConfig.addModeMappingForPassengers(modeMappingTrainLocalPublicTransport);
		
		ModeMappingForPassengersParameterSet modeMappingAirplane = new ModeMappingForPassengersParameterSet();
		modeMappingAirplane.setPassengerMode(TransportMode.airplane);
		modeMappingAirplane.setRouteMode(TransportMode.airplane);
		srrConfig.addModeMappingForPassengers(modeMappingAirplane);
		
		srrConfig.setUseIntermodalAccessEgress(true);
		
		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetAirportWithCar = new IntermodalAccessEgressParameterSet();
		intermodalAccessEgressParameterSetAirportWithCar.setMode("car");
		intermodalAccessEgressParameterSetAirportWithCar.setMaxRadius(200 * 1000);
		intermodalAccessEgressParameterSetAirportWithCar.setInitialSearchRadius(50 * 1000);
		intermodalAccessEgressParameterSetAirportWithCar.setSearchExtensionRadius(50 * 1000);
		intermodalAccessEgressParameterSetAirportWithCar.setStopFilterAttribute("type");
		intermodalAccessEgressParameterSetAirportWithCar.setStopFilterValue("airport");
		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetAirportWithCar);
		
//		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetTrainStationWithCar = new IntermodalAccessEgressParameterSet();
//		intermodalAccessEgressParameterSetTrainStationWithCar.setMode("car");
//		intermodalAccessEgressParameterSetTrainStationWithCar.setMaxRadius(100 * 1000);
//		intermodalAccessEgressParameterSetTrainStationWithCar.setInitialSearchRadius(20 * 1000);
//		intermodalAccessEgressParameterSetTrainStationWithCar.setSearchExtensionRadius(20 * 1000);
//		intermodalAccessEgressParameterSetTrainStationWithCar.setStopFilterAttribute("type");
//		intermodalAccessEgressParameterSetTrainStationWithCar.setStopFilterValue("trainStation");
//		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetTrainStationWithCar);
		
//		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar = new IntermodalAccessEgressParameterSet();
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setMode("car");
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setMaxRadius(50 * 1000);
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setInitialSearchRadius(10 * 1000);
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setSearchExtensionRadius(10 * 1000);
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setStopFilterAttribute("type");
//		intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar.setStopFilterValue("longDistanceTrain");
//		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetLongDistanceTrainStationWithCar);
//		
//		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetLocalTrainStationWithCar = new IntermodalAccessEgressParameterSet();
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setMode("car");
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setMaxRadius(20 * 1000);
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setInitialSearchRadius(5 * 1000);
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setSearchExtensionRadius(5 * 1000);
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setStopFilterAttribute("type");
//		intermodalAccessEgressParameterSetLocalTrainStationWithCar.setStopFilterValue("regionalTrain");
//		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetLocalTrainStationWithCar);
		
//		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetLocalPublicTransportWithCar = new IntermodalAccessEgressParameterSet();
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setMode("car");
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setMaxRadius(10 * 1000);
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setInitialSearchRadius(2 * 1000);
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setSearchExtensionRadius(2 * 1000);
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setStopFilterAttribute("type");
//		intermodalAccessEgressParameterSetLocalPublicTransportWithCar.setStopFilterValue("localPublicTransport");
//		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetLocalPublicTransportWithCar);
		
		IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSetWalk = new IntermodalAccessEgressParameterSet();
		intermodalAccessEgressParameterSetWalk.setMode("walk");
		intermodalAccessEgressParameterSetWalk.setMaxRadius(5 * 1000);
		intermodalAccessEgressParameterSetWalk.setInitialSearchRadius(1 * 1000);
		intermodalAccessEgressParameterSetWalk.setSearchExtensionRadius(1 * 1000);
		srrConfig.addIntermodalAccessEgress(intermodalAccessEgressParameterSetWalk);
		
		config.addModule(srrConfig);

		ScoringConfigGroup.ModeParams scoreCar = config.scoring().getModes().get(TransportMode.car);
		scoreCar.setMonetaryDistanceRate(-0.0001);
		scoreCar.setMarginalUtilityOfTraveling(-6);

		ScoringConfigGroup.ModeParams scorePt = config.scoring().getModes().get(TransportMode.pt);
		
//		ModeParams scoreTrain = new ModeParams(TransportMode.train);
//		scoreTrain.setConstant(scorePt.getConstant());
//		scoreTrain.setDailyMonetaryConstant(scorePt.getDailyMonetaryConstant());
//		scoreTrain.setDailyUtilityConstant(scorePt.getDailyUtilityConstant());
//		scoreTrain.setMarginalUtilityOfDistance(scorePt.getMarginalUtilityOfDistance());
//		scoreTrain.setMarginalUtilityOfTraveling(scorePt.getMarginalUtilityOfTraveling());
//		scoreTrain.setMonetaryDistanceRate(-0.0001);
//		config.scoring().addModeParams(scoreTrain);

		ScoringConfigGroup.ModeParams scoreLongDistanceTrain = new ScoringConfigGroup.ModeParams(longDistanceTrain);
		scoreLongDistanceTrain.setConstant(-6);
		scoreLongDistanceTrain.setDailyMonetaryConstant(scorePt.getDailyMonetaryConstant());
		scoreLongDistanceTrain.setDailyUtilityConstant(scorePt.getDailyUtilityConstant());
		scoreLongDistanceTrain.setMarginalUtilityOfDistance(scorePt.getMarginalUtilityOfDistance());
		scoreLongDistanceTrain.setMarginalUtilityOfTraveling(-3);
		scoreLongDistanceTrain.setMonetaryDistanceRate(-0.0001);
		config.scoring().addModeParams(scoreLongDistanceTrain);

		ScoringConfigGroup.ModeParams scoreRegionalTrain = new ScoringConfigGroup.ModeParams(regionalTrain);
		scoreRegionalTrain.setConstant(scorePt.getConstant());
		scoreRegionalTrain.setDailyMonetaryConstant(scorePt.getDailyMonetaryConstant());
		scoreRegionalTrain.setDailyUtilityConstant(scorePt.getDailyUtilityConstant());
		scoreRegionalTrain.setMarginalUtilityOfDistance(scorePt.getMarginalUtilityOfDistance());
		scoreRegionalTrain.setMarginalUtilityOfTraveling(-3);
		scoreRegionalTrain.setMonetaryDistanceRate(-0.0001);
		config.scoring().addModeParams(scoreRegionalTrain);

		ScoringConfigGroup.ModeParams scoreLocalPublicTransport = new ScoringConfigGroup.ModeParams(localPublicTransport);
		scoreLocalPublicTransport.setConstant(scorePt.getConstant());
		scoreLocalPublicTransport.setDailyMonetaryConstant(scorePt.getDailyMonetaryConstant());
		scoreLocalPublicTransport.setDailyUtilityConstant(scorePt.getDailyUtilityConstant());
		scoreLocalPublicTransport.setMarginalUtilityOfDistance(scorePt.getMarginalUtilityOfDistance());
		scoreLocalPublicTransport.setMarginalUtilityOfTraveling(-3);
		scoreLocalPublicTransport.setMonetaryDistanceRate(0);
		config.scoring().addModeParams(scoreLocalPublicTransport);

		ScoringConfigGroup.ModeParams scoreAirplane = new ScoringConfigGroup.ModeParams(TransportMode.airplane);
		scoreAirplane.setConstant(-15);
		scoreAirplane.setDailyMonetaryConstant(scorePt.getDailyMonetaryConstant());
		scoreAirplane.setDailyUtilityConstant(scorePt.getDailyUtilityConstant());
		scoreAirplane.setMarginalUtilityOfDistance(scorePt.getMarginalUtilityOfDistance());
		scoreAirplane.setMarginalUtilityOfTraveling(-6);
		scoreAirplane.setMonetaryDistanceRate(-0.0001);
		config.scoring().addModeParams(scoreAirplane);
		
		ConfigUtils.applyCommandline( config, typedArgs ) ;
		Scenario scenario = ScenarioUtils.loadScenario(config) ;
		
		Network trainNetwork = NetworkUtils.readNetwork(inputNetworkTrain);
		Set<String> trainModes = new HashSet<>();
		trainModes.add(TransportMode.train);
		trainModes.add(longDistanceTrain);
		trainModes.add(regionalTrain);
		trainModes.add(localPublicTransport);
		trainNetwork.getLinks().values().forEach(l -> l.setAllowedModes(trainModes));
		MergeNetworks.merge(scenario.getNetwork(),"", trainNetwork);
		
		Config trainConfig = ConfigUtils.createConfig();
		trainConfig.transit().setTransitScheduleFile(inputScheduleTrain);
		trainConfig.transit().setVehiclesFile(inputVehiclesTrain);
		Scenario trainScenario = ScenarioUtils.loadScenario(trainConfig);
//		trainScenario.getTransitSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> route.setTransportMode(TransportMode.train)));
//		trainScenario.getTransitSchedule().getFacilities().values().forEach(stop -> TransitScheduleUtils.putStopFacilityAttribute(stop, "type", "trainStation"));
		mergeSchedules(scenario.getTransitSchedule(), trainScenario.getTransitSchedule());
		mergeVehicles(scenario.getTransitVehicles(), trainScenario.getTransitVehicles());
		
//		Network DBNetwork_2030 = NetworkUtils.readNetwork(inputNetworkTrain_2030);
//		Set<String> trainModes_2030 = new HashSet<>();
//		trainModes_2030.add(TransportMode.train);
//		DBNetwork_2030.getLinks().values().forEach(l -> l.setAllowedModes(trainModes_2030));
//		MergeNetworks.merge(scenario.getNetwork(),"", DBNetwork_2030);
//		
//		Config trainConfig_2030 = ConfigUtils.createConfig();
//		trainConfig_2030.transit().setTransitScheduleFile(inputScheduleTrain_2030);
//		trainConfig_2030.transit().setVehiclesFile(inputVehiclesTrain_2030);
//		Scenario trainScenario_2030 = ScenarioUtils.loadScenario(trainConfig_2030);
//		trainScenario_2030.getTransitSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> route.setTransportMode(TransportMode.train)));
//		trainScenario_2030.getTransitSchedule().getFacilities().values().forEach(stop -> TransitScheduleUtils.putStopFacilityAttribute(stop, "type", "trainStation"));
//		mergeSchedules(scenario.getTransitSchedule(), trainScenario_2030.getTransitSchedule());
//		mergeVehicles(scenario.getTransitVehicles(), trainScenario_2030.getTransitVehicles());
		
		Network airplaneNetwork = NetworkUtils.readNetwork(inputDir + inputNetworkPlane);
		Set<String> airplaneModes = new HashSet<>();
		airplaneModes.add(TransportMode.airplane);
		airplaneNetwork.getLinks().values().forEach(l -> l.setAllowedModes(airplaneModes));
		MergeNetworks.merge(scenario.getNetwork(),"", airplaneNetwork);
		
		Config airplaneConfig = ConfigUtils.createConfig();
		airplaneConfig.transit().setTransitScheduleFile(inputDir + inputSchedulePlane);
		airplaneConfig.transit().setVehiclesFile(inputDir + inputVehiclesPlane);
		Scenario airplaneScenario = ScenarioUtils.loadScenario(airplaneConfig);
		airplaneScenario.getTransitSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> route.setTransportMode(TransportMode.airplane)));
		airplaneScenario.getTransitSchedule().getFacilities().values().forEach(stop -> stop.getAttributes().putAttribute("type", "airport"));
		mergeSchedules(scenario.getTransitSchedule(), airplaneScenario.getTransitSchedule());
		mergeVehicles(scenario.getTransitVehicles(), airplaneScenario.getTransitVehicles());
		
		Controler controler = new Controler( scenario ) ;
		controler.addOverridingModule(new SwissRailRaptorModule());
		
		controler.addOverridingModule( new AbstractModule(){
			@Override public void install() {
//				this.bindScoringFunctionFactory().to( MyScoringFunctionFactory.class ) ;
//				install( new SwissRailRaptorModule() );
				bind(RaptorParametersForPerson.class).to(AirplaneTrainSwitcherIndividualRaptorParametersForPerson.class);
				bind(AnalysisMainModeIdentifier.class).to(MyMainModeIdentifier.class);
			}
		} );

		controler.addOverridingModule(new SimWrapperModule());

		ConfigUtils.writeConfig(scenario.getConfig(), inputDir + "configGermany.xml");
		
		NetworkUtils.writeNetwork(scenario.getNetwork(), inputDir + "GermanyNetwork.xml");
		new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(inputDir + "GermanyTransitVehicles.xml");
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(inputDir + "GermanyTransitSchedule.xml");
		
		controler.run();

	}
	
	private static void mergeSchedules(TransitSchedule schedule, TransitSchedule toBeMerged) {
		toBeMerged.getFacilities().values().forEach(schedule::addStopFacility);
		toBeMerged.getTransitLines().values().forEach(schedule::addTransitLine);
	}

	private static void mergeVehicles(Vehicles vehicles, Vehicles toBeMerged) {
		toBeMerged.getVehicleTypes().values().forEach(vehicles::addVehicleType);
		toBeMerged.getVehicles().values().forEach(vehicles::addVehicle);
	}

}

class MyScoringFunctionFactory implements ScoringFunctionFactory {

	@Override
	public ScoringFunction createNewScoringFunction(Person person) {
		// TODO Auto-generated method stub
		return new MyScoringFunction();
	}
	
}

class MyScoringFunction implements ScoringFunction {
	
	private double score;

	@Override
	public void handleActivity(Activity activity) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handleLeg(Leg leg) {
		score -= leg.getTravelTime().seconds();
		
		if (leg.getMode().equals(TransportMode.airplane) ) {
			score -= 2 * 3600;
		
		}
		
		
	}

	@Override
	public void agentStuck(double time) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void addMoney(double amount) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void addScore(double v) {
		// TODO Auto-generated method stub
	}

	@Override
	public void finish() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public double getScore() {
		// TODO Auto-generated method stub
		return score;
	}

	@Override
	public void handleEvent(Event event) {
		// TODO Auto-generated method stub
		
	}
	
}
