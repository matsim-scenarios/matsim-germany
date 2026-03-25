package org.matsim.prepare.longDistanceFreightGER;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.options.LanduseOptions;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ProjectionUtils;

import java.io.IOException;
import java.util.*;

import static org.matsim.prepare.longDistanceFreightGER.GenerateFreightPlans.*;

class FreightAgentGenerator {
    private final LocationCalculator roadLocationCalculator;
	private final LocationCalculator railwayLocationCalculator;
	private final DepartureTimeCalculator departureTimeCalculator;
    private final NumOfTripsCalculator numOfTruckTripsCalculator;
    private final PopulationFactory populationFactory;
    private final Network network;
	private final Set<String> modes;
	private final double sample;

    public FreightAgentGenerator(Network network, String shpPath, LanduseOptions landUse, Set<String> modes, double averageTruckLoad, int workingDays, double sample) throws IOException {
        this.roadLocationCalculator = new DefaultLocationCalculator(network, shpPath, landUse, TransportMode.car);
		this.railwayLocationCalculator = new ZoneCentroidLocationCalculator(shpPath, ProjectionUtils.getCRS(network));
		this.departureTimeCalculator = new DefaultDepartureTimeCalculator();
        this.numOfTruckTripsCalculator = new DefaultNumberOfTripsCalculator(averageTruckLoad, workingDays, sample);
		this.populationFactory = PopulationUtils.getFactory();
        this.network = network;
		this.modes = modes;
		this.sample = sample;
    }

    public List<Person> generateFreightAgents(TripRelation tripRelation, String tripRelationId) {
        List<Person> freightAgents = new ArrayList<>();
        String preRunMode = tripRelation.getModePreRun();
        String mainRunMode = tripRelation.getModeMainRun();
        String postRunMode = tripRelation.getModePostRun();

		// Currently we create separate persons for pre, main and post legs. Merging that into one person is not trivial, because multiple
		// trucks on the pre leg might fill a single train on the main leg.

        int numOfTruckTrips = numOfTruckTripsCalculator.calculateNumberOfTrips(tripRelation.getTonsPerYear(), tripRelation.getGoodsType());
		// trains/day is probably 0 for most relations and train loads vary a lot. For the time being have 1 train/year instead
		int numOfTrainTrips = 1;
		// instead interpret sample size as include freight relation at all or not
		Random railSampler = MatsimRandom.getLocalInstance();

		if (preRunMode.equals(TripRelation.ModesInputData.road) && modes.contains(preRunMode)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "pre";
				String startCell = tripRelation.getOriginCell();
				String endCell = tripRelation.getOriginCellMainRun();
				String mode = LEG_MODE_FREIGHT_ROAD;
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

		// main-run is railway. Sample here instead of varying the number of trips
		if (mainRunMode.equals(TripRelation.ModesInputData.rail) && modes.contains(mainRunMode) && railSampler.nextDouble() < sample) {
			for (int i = 0; i < numOfTrainTrips; i++) {
				String preMainPost = "main";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = LEG_MODE_FREIGHT_RAIL;
				LocationCalculator locationCalculator = railwayLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}


		if (mainRunMode.equals(TripRelation.ModesInputData.road) && modes.contains(mainRunMode)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "main";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = LEG_MODE_FREIGHT_ROAD;
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

		// post-run
		if (postRunMode.equals(TripRelation.ModesInputData.road) && modes.contains(postRunMode)) {
			for (int i = 0; i < numOfTruckTrips; i++) {
				String preMainPost = "post";
				String startCell = tripRelation.getOriginCellMainRun();
				String endCell = tripRelation.getDestinationCellMainRun();
				String mode = LEG_MODE_FREIGHT_ROAD;
				LocationCalculator locationCalculator = roadLocationCalculator;

				createTrip(tripRelation, tripRelationId, mode, i, preMainPost, locationCalculator, startCell, endCell, freightAgents);
			}
		}

        return freightAgents;
    }

	private void createTrip(TripRelation tripRelation, String tripRelationId, String mode, int i, String preMainPost, LocationCalculator locationCalculator, String startCell, String endCell, List<Person> freightAgents) {
		Person person = populationFactory.createPerson(Id.createPersonId(LONG_DISTANCE_FREIGHT + "_" + tripRelationId + "_" + i + "_" + preMainPost));
		Plan plan = populationFactory.createPlan();
		double departureTime = departureTimeCalculator.getDepartureTime();

		Coord startCoord = locationCalculator.getCoord(startCell);
		Activity startAct = populationFactory.createActivityFromCoord("freight_start", startCoord);
		startAct.setEndTime(departureTime);
		plan.addActivity(startAct);

		Leg leg = populationFactory.createLeg(mode);
		leg.getAttributes().putAttribute("tonsPerYear", tripRelation.getTonsPerYear());
		plan.addLeg(leg);

		Coord endCoord = locationCalculator.getCoord(endCell);
		Activity endAct = populationFactory.createActivityFromCoord("freight_end", endCoord);
		plan.addActivity(endAct);

		person.addPlan(plan);
		person.getAttributes().putAttribute("trip_type", preMainPost + "-run");
		writeCommonAttributes(person, tripRelation, tripRelationId);

		freightAgents.add(person);
	}

	// TODO store this attribute names as public static strings
    private void writeCommonAttributes(Person person, TripRelation tripRelation, String tripRelationId){
        person.getAttributes().putAttribute("subpopulation", LONG_DISTANCE_FREIGHT);
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
		Coord getCoord(String verkehrszelle);
    }

    public interface DepartureTimeCalculator {
        double getDepartureTime();
    }

    public interface NumOfTripsCalculator {
        int calculateNumberOfTrips(double tonsPerYear, String goodsType);
    }

}
