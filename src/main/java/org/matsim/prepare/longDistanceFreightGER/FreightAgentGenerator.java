package org.matsim.prepare.longDistanceFreightGER;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.options.LanduseOptions;
import org.matsim.core.population.PopulationUtils;

import java.io.IOException;
import java.util.*;

class FreightAgentGenerator {
    private final LocationCalculator roadLocationCalculator;
	private final LocationCalculator railwayLocationCalculator;
	private final DepartureTimeCalculator departureTimeCalculator;
    private final NumOfTripsCalculator numOfTruckTripsCalculator;
	private final NumOfTripsCalculator numOfTrainTripsCalculator;
    private final PopulationFactory populationFactory;
    private final Network network;

    public FreightAgentGenerator(Network network, String shpPath, LanduseOptions landUse, double averageTruckLoad, double averageTrainLoad, int workingDays, double sample) throws IOException {
        this.roadLocationCalculator = new DefaultLocationCalculator(network, shpPath, landUse);
		this.railwayLocationCalculator = new DefaultLocationCalculator(network, shpPath, landUse);// TODO other implementation
		this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.numOfTruckTripsCalculator = new DefaultNumberOfTripsCalculator(averageTruckLoad, workingDays, sample);
		this.numOfTrainTripsCalculator = new DefaultNumberOfTripsCalculator(averageTrainLoad, workingDays, sample);
		this.populationFactory = PopulationUtils.getFactory();
        this.network = network;
    }

    public List<Person> generateFreightAgents(TripRelation tripRelation, String tripRelationId) {
        List<Person> freightAgents = new ArrayList<>();
        String preRunMode = tripRelation.getModePreRun();
        String mainRunMode = tripRelation.getModeMainRun();
        String postRunMode = tripRelation.getModePostRun();

        int numOfTruckTrips = numOfTruckTripsCalculator.calculateNumberOfTrips(tripRelation.getTonsPerYear(), tripRelation.getGoodsType());
		int numOfTrainTrips = numOfTrainTripsCalculator.calculateNumberOfTrips(tripRelation.getTonsPerYear(), tripRelation.getGoodsType());

		if (preRunMode.equals(TripRelation.ModesInputData.road)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "pre";
				String startCell = tripRelation.getOriginCell();
				String endCell = tripRelation.getOriginCellMainRun();
				String mode = "freight";
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

		// main-run is railway
		if (mainRunMode.equals(TripRelation.ModesInputData.rail)) {
			for (int i = 0; i < numOfTrainTrips; i++) {
				String preMainPost = "main";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = "freightRail";
				LocationCalculator locationCalculator = railwayLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}


		if (mainRunMode.equals(TripRelation.ModesInputData.road)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "main";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = "freight";
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

		// post-run
		if (postRunMode.equals(TripRelation.ModesInputData.road)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "post";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = "freight";
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

        return freightAgents;
    }

	private void createTrip(TripRelation tripRelation, String tripRelationId, String mode, int i, String preMainPost, LocationCalculator locationCalculator, String startCell, String endCell, List<Person> freightAgents) {
		Person person = populationFactory.createPerson(Id.createPersonId(mode + "_" + tripRelationId + "_" + i + "_" + preMainPost));
		Plan plan = populationFactory.createPlan();
		double departureTime = departureTimeCalculator.getDepartureTime();

		Id<Link> startLinkId = locationCalculator.getLocationOnNetwork(startCell);
		Activity startAct = populationFactory.createActivityFromLinkId("freight_start", startLinkId);
		startAct.setCoord(network.getLinks().get(startLinkId).getToNode().getCoord());
		startAct.setEndTime(departureTime);
		plan.addActivity(startAct);

		Leg leg = populationFactory.createLeg(mode);
		leg.getAttributes().putAttribute("tonsPerYear", tripRelation.getTonsPerYear());
		plan.addLeg(leg);

		Id<Link> endLinkId = locationCalculator.getLocationOnNetwork(endCell);
		Activity endAct = populationFactory.createActivityFromLinkId("freight_end", endLinkId);
		endAct.setCoord(network.getLinks().get(endLinkId).getToNode().getCoord());
		plan.addActivity(endAct);

		person.addPlan(plan);
		person.getAttributes().putAttribute("trip_type", preMainPost + "-run");
		writeCommonAttributes(person, tripRelation, tripRelationId);

		freightAgents.add(person);
	}

	// TODO store this attribute names as public static strings
    private void writeCommonAttributes(Person person, TripRelation tripRelation, String tripRelationId){
        person.getAttributes().putAttribute("subpopulation", "freight");
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

    public interface LocationCalculator {
        Id<Link> getLocationOnNetwork(String verkehrszelle);
    }

    public interface DepartureTimeCalculator {
        double getDepartureTime();
    }

    public interface NumOfTripsCalculator {
        int calculateNumberOfTrips(double tonsPerYear, String goodsType);
    }

}
