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
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.pt.transitSchedule.TransitScheduleWriterV2;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.vehicles.MatsimVehicleWriter;

/**
 * @author smueller
 */

public class CreateBERCGN2030timetable {

	public static void main(String[] args) {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);

		TransitScheduleFactory tsf = scenario.getTransitSchedule().getFactory();
		TransitStopFacility stopBerlin = tsf.createTransitStopFacility(Id.create("Berlin Hbf 2030", TransitStopFacility.class), CoordUtils.createCoord(3796562.713066836, 5830370.508157048), false);
		TransitStopFacility stopSpandau = tsf.createTransitStopFacility(Id.create("Berlin Spandau 2030", TransitStopFacility.class), CoordUtils.createCoord(3784794.804324691, 5830681.959281431), false);
		TransitStopFacility stopKoeln = tsf.createTransitStopFacility(Id.create("Koeln Hbf 2030", TransitStopFacility.class), CoordUtils.createCoord(3356610.9922550013, 5647290.333069744), false);
		scenario.getTransitSchedule().addStopFacility(stopBerlin);
		scenario.getTransitSchedule().addStopFacility(stopSpandau);
		scenario.getTransitSchedule().addStopFacility(stopKoeln);

		TransitLine line = tsf.createTransitLine(Id.create("ICE Berlin Koeln 2030", TransitLine.class));
		List<TransitRouteStop> stops = new ArrayList<>();
		TransitRouteStop routeStopBerlin = tsf.createTransitRouteStop(stopBerlin, 0, 0);
		TransitRouteStop routeStopSpandau = tsf.createTransitRouteStop(stopSpandau, 10. * 60, 12. * 60);
		TransitRouteStop routeStopKoeln = tsf.createTransitRouteStop(stopKoeln, 4. * 3600, 4. * 3600);
		stops.add(routeStopBerlin);
		stops.add(routeStopSpandau);
		stops.add(routeStopKoeln);
		TransitRoute route = tsf.createTransitRoute(Id.create("ICE Berlin Koeln 2030", TransitRoute.class), null, stops, "rail");
		for (int ii = 5 * 3600; ii <= 21 * 3600; ii = ii + 1800) {
			Departure departure = tsf.createDeparture(Id.create(ii, Departure.class), ii);
			route.addDeparture(departure);

		}
		line.addRoute(route);
		scenario.getTransitSchedule().addTransitLine(line);


		new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), "2030_").createNetwork();
		new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();
		RunGTFS2MATSim.setLinkSpeedsToMax(scenario);
		new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile("2030_transitVehicles.xml.gz");
		new TransitScheduleWriterV2(scenario.getTransitSchedule()).write("2030_transitSchedule.xml.gz");
		new NetworkWriter(scenario.getNetwork()).write("2030_network.xml.gz");

	}

}
