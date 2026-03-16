package org.matsim.plane;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * This class provides a parser for the EUROCONTROL flight data and generates a MATSim schedule. <br/><br/>
 *
 * The <i>EUROCONTROL Aviation Data Repository for Research</i> (ADRR) datasets are available <a href="https://www.eurocontrol.int/dashboard/aviation-data-research"/>here<a/>.
 * This class only needs the basic <i>Flight</i> dataset. The data is free for Research and development purposes, updated every quarter and includes
 * all flights from a given month two years prior. <i>Example: The currently used dataset for 12/2023 was released in 01/2026.</i> <br/><br/>
 *
 * This class also generates a plane-network, containing airports and connection links which can be later fused with the given scenario network
 * using the {@link AirportNetworkFuser}.
 */
public class CreatePlaneSchedule implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger(CreatePlaneSchedule.class);

	@CommandLine.Option(names = "--adrr-flight-data", description = "Path to the flights file of the EUROCONTROL ADRR dataset", required = true)
	private String flightPath;

	@CommandLine.Option(names = "--sample-day", description = "The day to sample from the dataset. Make sure to give a date with no holidays or other unusual events. Format: DD-MM-YYYY", required = true)
	private String sampleDay;

	@CommandLine.Option(names = "--network-output", description = "Path to output network", required = true)
	private String outputNetwork;

	@CommandLine.Option(names = "--schedule-output", description = "Path to output schedule", required = true)
	private String outputSchedule;

	@CommandLine.Mixin
	private CrsOptions crs = new CrsOptions("EPSG:4326");

	public static void main(String[] args) {
		new CreatePlaneSchedule().execute(args);
	}

	@Override
	public Integer call() {
		// Read in the Flights CSV
		Set<Tuple<String, Coord>> airportSet = new HashSet<>();
		List<FlightInformation> flightInformationList = new LinkedList<>();
		CoordinateTransformation transformation = crs.getTransformation();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

		var format = CSVFormat.DEFAULT.builder()
			.setDelimiter(",")
			.setSkipHeaderRecord(true)
			.setHeader()
			.build();

		try (var reader = Files.newBufferedReader(Path.of(flightPath)); var parser = CSVParser.parse(reader, format)) {
			for(var record : parser){
				flightInformationList.add( new FlightInformation(
					record.get("ADEP"),
					record.get("ADES"),
					LocalDateTime.parse(record.get("FILED OFF BLOCK TIME"), formatter),
					LocalDateTime.parse(record.get("FILED ARRIVAL TIME"), formatter)
				));

				String lonADEP = record.get("ADEP Longitude");
				String latADEP = record.get("ADEP Latitude");
				String lonADES = record.get("ADES Longitude");
				String latADES = record.get("ADES Latitude");

				if(!lonADEP.isEmpty() && !latADEP.isEmpty()){
					airportSet.add(new Tuple<>(record.get("ADEP"), transformation.transform(new Coord(Double.parseDouble(lonADEP), Double.parseDouble(latADEP)))));
				} else {
					log.warn("Skipped ADEP airport {} due to missing coordinates!", record.get("ADEP"));
				}

				if(!lonADES.isEmpty() && !latADES.isEmpty()) {
					airportSet.add(new Tuple<>(record.get("ADES"), transformation.transform(new Coord(Double.parseDouble(lonADES), Double.parseDouble(latADES)))));
				} else {
					log.warn("Skipped ADES airport {} due to missing coordinates!", record.get("ADES"));
				}
			}
		} catch(IOException e){
			throw new RuntimeException(e);
		}

		// Create the plane-network and add airports into it (without connections)
		Network planeNetwork = NetworkUtils.createNetwork();
		for(var airport : airportSet){
			addAirportToNetwork(planeNetwork, airport);
		}

		// Create schedule with given flightInformation
		// TODO

		// Print output files
		NetworkUtils.writeNetwork(planeNetwork, outputNetwork);
		// TODO schedule

		return (Integer) 0;
	}

	// TODO Complete airport
	private void addAirportToNetwork(Network planeNetwork, Tuple<String, Coord> airport){
		List<Node> airportNodes = new ArrayList<>();
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "_n0"), airport.getSecond()));
		airportNodes.add(NetworkUtils.createNode(Id.createNodeId(airport.getFirst() + "_n1"), CoordUtils.plus(airport.getSecond(), new Coord(1000, 0))));

		for(var node : airportNodes){
			planeNetwork.addNode(node);
		}

		planeNetwork.addLink(NetworkUtils.createLink(Id.createLinkId(airport.getFirst() + "_runway"), airportNodes.get(0), airportNodes.get(1), planeNetwork, 1000, 300, 1000, 1));
	}

	record FlightInformation(String ADEP, String ADES, LocalDateTime filedOffBlockTime, LocalDateTime filedArrivalTime){}
}
