/* *********************************************************************** *
 * project: org.matsim.*
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


import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.TransitScheduleWriterV2;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;

import com.conveyal.gtfs.GTFSFeed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ikaddoura, smueller
 */

public class CreatePtScheduleAndVehiclesFromGtfs {

    private static final Logger log = Logger.getLogger(CreatePtScheduleAndVehiclesFromGtfs.class);

    public Scenario run(String gtfsZipFile, String date, String networkPrefix) {

        final CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:31467");
        final LocalDate localDate = LocalDate.parse(date);

        log.info("GTFS zip file: " + gtfsZipFile);
        
        // Convert GTFS to matsim transit schedule
        Scenario scenario = createScenarioFromGtfsFile(gtfsZipFile, localDate, ct);
//        scenario.getConfig().global().setCoordinateSystem("WGS84");
//        scenario.getConfig().network().setInputCRS("WGS84");
        
        //Create a network around the schedule
//        new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), networkPrefix).createNetwork();
        
        //Create simple transit vehicles with a pcu of 0
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();
        scenario.getTransitVehicles().getVehicleTypes().forEach((id, type) -> type.setPcuEquivalents(0));
        
        // correct network
        scenario.getNetwork().getLinks().values().stream()
                .filter(this::hasImplausibleLength).forEach(implausibleLink -> {
            log.warn("Link length is " + implausibleLink.getLength() + ". Adjust link length for link " + implausibleLink.getId());
            implausibleLink.setLength(1.234);
        });

        // correct schedule
        List<Id<TransitStopFacility>> wrongStopIDs = new ArrayList<>();
        List<Id<TransitLine>> linesWithWrongStopIDs = new ArrayList<>();

        scenario.getTransitSchedule().getFacilities().values().stream()
                .filter(this::hasImplausibleCoordinate)
                .forEach(implausibleStop -> {
                    log.warn("Transit stop coordinate is " + implausibleStop.getCoord().toString() + ". Adding stop " + implausibleStop.getId() + " / " + implausibleStop.getName() + " to the list of wrong stops...");
                    wrongStopIDs.add(implausibleStop.getId());
                });

        // get lines for these stops
        for (Id<TransitStopFacility> id : wrongStopIDs) {
            for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
                for (TransitRoute route : line.getRoutes().values()) {
                    for (TransitRouteStop stop : route.getStops()) {
                        if (stop.getStopFacility().getId().toString().equals(id.toString())) {
                            linesWithWrongStopIDs.add(line.getId());
                        }
                    }
                }
            }
        }

        TransitSchedule tS = makeTransitScheduleModifiable(scenario.getTransitSchedule());

        // remove stops
        for (Id<TransitStopFacility> id : wrongStopIDs) {
            log.warn("Removing stop Id " + id);
            tS.getFacilities().remove(id);
        }

        // remove lines
        for (Id<TransitLine> id : linesWithWrongStopIDs) {
            log.warn("Removing transit line " + id);
            tS.getTransitLines().remove(id);
        }
        
        return scenario;
    }

    private TransitSchedule makeTransitScheduleModifiable(TransitSchedule transitSchedule) {
        Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config );
    	TransitSchedule tS = scenario.getTransitSchedule();

        for (TransitStopFacility stop : transitSchedule.getFacilities().values()) {
            tS.addStopFacility(stop);
        }

        for (TransitLine line : transitSchedule.getTransitLines().values()) {
            tS.addTransitLine(line);
        }

        return tS;
    }

    private Scenario createScenarioFromGtfsFile(String fromFile, LocalDate date, CoordinateTransformation transformation) {

        GTFSFeed feed = GTFSFeed.fromFile(fromFile);
        feed.feedInfo.values().stream().findFirst().ifPresent((feedInfo) -> {
            System.out.println("Feed start date: " + feedInfo.feed_start_date);
            System.out.println("Feed end date: " + feedInfo.feed_end_date);
        });
        System.out.println("Parsed trips: " + feed.trips.size());
        System.out.println("Parsed routes: " + feed.routes.size());
        System.out.println("Parsed stops: " + feed.stops.size());
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        GtfsConverter converter = GtfsConverter.newBuilder()
                .setFeed(feed)
                .setScenario(scenario)
                .setTransform(transformation)
                .setUseExtendedRouteTypes(false)
                .setDate(date)
                .build();
        converter.convert();
        System.out.println("Converted stops: " + scenario.getTransitSchedule().getFacilities().size());
        
        return scenario;
    }

    private boolean hasImplausibleCoordinate(TransitStopFacility stop) {
        return !(stop.getCoord().getX() > Double.NEGATIVE_INFINITY) || !(stop.getCoord().getX() < Double.POSITIVE_INFINITY) ||
                !(stop.getCoord().getY() > Double.NEGATIVE_INFINITY) || !(stop.getCoord().getY() < Double.POSITIVE_INFINITY);
    }

    private boolean hasImplausibleLength(Link link) {
        return !(link.getLength() > 0) || !(link.getLength() < Double.POSITIVE_INFINITY);
    }

}

