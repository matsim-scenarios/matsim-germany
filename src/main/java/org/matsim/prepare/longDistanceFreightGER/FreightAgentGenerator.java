package org.matsim.prepare.longDistanceFreightGER;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.common.conventions.vsp.SubpopulationDefaultNames;
import org.matsim.core.population.PopulationUtils;

import java.io.IOException;
import java.util.*;

class FreightAgentGenerator {
    private final LocationCalculator locationCalculator;
    private final DepartureTimeCalculator departureTimeCalculator;
    private final NumOfTripsCalculator numOfTripsCalculator;
    private final PopulationFactory populationFactory;

    public FreightAgentGenerator(Network network, String shpPath, String landuseShp, Set<String> landUseTypes, double averageLoad, int workingDays, double sample) throws IOException {
        this.locationCalculator = new DefaultLocationCalculator(network, shpPath, landuseShp, landUseTypes);
        this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.numOfTripsCalculator = new DefaultNumberOfTripsCalculator(averageLoad, workingDays, sample);
        this.populationFactory = PopulationUtils.getFactory();
    }

    public List<Person> generateFreightAgents(TripRelation tripRelation, String tripRelationId) {
        List<Person> freightAgents = new ArrayList<>();
        String preRunMode = tripRelation.getModePreRun();
        String mainRunMode = tripRelation.getModeMainRun();
        String postRunMode = tripRelation.getModePostRun();

        if (!preRunMode.equals("2") && !mainRunMode.equals("2") && !postRunMode.equals("2")) {
            return freightAgents; // This trip relation is irrelevant as it does not contain any freight by road
        }

        int numOfTrips = numOfTripsCalculator.calculateNumberOfTrips(tripRelation.getTonsPerYear(), tripRelation.getGoodsType());
        for (int i = 0; i < numOfTrips; i++) {
            // pre-run
            if (preRunMode.equals("2")) {
                Person person = populationFactory.createPerson(Id.createPersonId("freight_" + tripRelationId + "_" + i + "_pre"));
                Plan plan = populationFactory.createPlan();
                double departureTime = departureTimeCalculator.getDepartureTime();

                Location startLocation = locationCalculator.getLocation(tripRelation.getOriginCell());
                Activity startAct = createActivity("freight_start", startLocation);
                startAct.setEndTime(departureTime);
                plan.addActivity(startAct);

                Leg leg = populationFactory.createLeg("freight");
                plan.addLeg(leg);

                Location endLocation = locationCalculator.getLocation(tripRelation.getOriginCellMainRun());
                Activity endAct = createActivity("freight_end", endLocation);
                plan.addActivity(endAct);

                person.addPlan(plan);
                person.getAttributes().putAttribute("trip_type", "pre-run");
                writeCommonAttributes(person, tripRelation, tripRelationId);

                freightAgents.add(person);
            }

            // main-run
            if (mainRunMode.equals("2")) {
                Person person = populationFactory.createPerson(Id.createPersonId("freight_" + tripRelationId + "_" + i + "_main"));
                Plan plan = populationFactory.createPlan();
                double departureTime = departureTimeCalculator.getDepartureTime();

                Location startLocation = locationCalculator.getLocation(tripRelation.getOriginCellMainRun());
                Activity startAct = createActivity("freight_start", startLocation);
                startAct.setEndTime(departureTime);
                plan.addActivity(startAct);

                Leg leg = populationFactory.createLeg("freight");
                plan.addLeg(leg);

                Location endLocation = locationCalculator.getLocation(tripRelation.getDestinationCellMainRun());
                Activity endAct = createActivity("freight_end", endLocation);
                plan.addActivity(endAct);

                person.addPlan(plan);
                person.getAttributes().putAttribute("trip_type", "main-run");
                writeCommonAttributes(person, tripRelation, tripRelationId);

                freightAgents.add(person);
            }

            // post-run
            if (postRunMode.equals("2")) {
                Person person = populationFactory.createPerson(Id.createPersonId("freight_" + tripRelationId + "_" + i + "_post"));
                Plan plan = populationFactory.createPlan();
                double departureTime = departureTimeCalculator.getDepartureTime();

                Location startLocation = locationCalculator.getLocation(tripRelation.getDestinationCellMainRun());
                Activity startAct = createActivity("freight_start", startLocation);
                startAct.setEndTime(departureTime);
                plan.addActivity(startAct);

                Leg leg = populationFactory.createLeg("freight");
                plan.addLeg(leg);

                Location endLocation = locationCalculator.getLocation(tripRelation.getDestinationCell());
                Activity endAct = createActivity("freight_end", endLocation);
                plan.addActivity(endAct);

                person.addPlan(plan);
                person.getAttributes().putAttribute("trip_type", "post-run");
                writeCommonAttributes(person, tripRelation, tripRelationId);

                freightAgents.add(person);
            }
        }

        return freightAgents;
    }

    /**
     * Creates an activity on the selected link. Routing uses the link id, while the coordinate keeps the sampled
     * location, e.g. the point inside a matching landuse polygon.
     */
    private Activity createActivity(String type, Location location) {
        Activity activity = populationFactory.createActivityFromLinkId(type, location.linkId());
        activity.setCoord(location.coord());
        if (!"network-link".equals(location.source())) {
            activity.getAttributes().putAttribute("location_source", location.source());
        }
        if (location.landuseType() != null) {
            activity.getAttributes().putAttribute("landuse_type", location.landuseType());
        }
        return activity;
    }

    // TODO store this attribute names as public static strings
    private void writeCommonAttributes(Person person, TripRelation tripRelation, String tripRelationId){
        person.getAttributes().putAttribute("subpopulation", SubpopulationDefaultNames.SUBPOP_LONG_DISTANCE_FREIGHT);
        person.getAttributes().putAttribute("trip_relation_index", tripRelationId);
        person.getAttributes().putAttribute("pre-run_mode", tripRelation.getModePreRun());
        person.getAttributes().putAttribute("main-run_mode", tripRelation.getModeMainRun());
        person.getAttributes().putAttribute("post-run_mode", tripRelation.getModePostRun());
        person.getAttributes().putAttribute("initial_origin_cell", tripRelation.getOriginCell());
        person.getAttributes().putAttribute("origin_cell_main_run", tripRelation.getOriginCellMainRun());
        person.getAttributes().putAttribute("destination_cell_main_run", tripRelation.getDestinationCellMainRun());
        person.getAttributes().putAttribute("final_destination_cell", tripRelation.getDestinationCell());
        person.getAttributes().putAttribute("goods_type", tripRelation.getGoodsType());
        person.getAttributes().putAttribute("tons_per_year", tripRelation.getTonsPerYear());
    }

    /**
     * Holds the selected routing link, the coordinate written to the activity, and optional provenance information for
     * debugging landuse-based locations.
     */
    public record Location(Id<Link> linkId, Coord coord, String source, String landuseType) {
    }

    public interface LocationCalculator {
        Location getLocation(String verkehrszelle);
    }

    public interface DepartureTimeCalculator {
        double getDepartureTime();
    }

    public interface NumOfTripsCalculator {
        int calculateNumberOfTrips(double tonsPerYear, String goodsType);
    }

}
