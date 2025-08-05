package org.matsim.prepare.longDistanceFreightGER;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.options.LanduseOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.core.utils.geometry.transformations.IdentityTransformation;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Determine the start and end locations of the freight trips based on the NUTS3 shape file and the land use data (e.g., industry, retail, commercial)
 */
class DefaultLocationCalculator implements FreightAgentGenerator.LocationCalculator {
	private final static Logger logger = LogManager.getLogger(DefaultLocationCalculator.class);
	private final Random rnd = new Random(5678);
	private final LanduseOptions landUse;
	private final Network network;
	private final Map<String, List<Id<Link>>> mapping = new HashMap<>();
	private final ShpOptions shp;
	private static final String lookUpTablePath = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/" +
		"scenarios/countries/de/german-wide-freight/v2/processed-data/complete-lookup-table.csv";

	public DefaultLocationCalculator(Network network, String shpFilePath, LanduseOptions landUse) throws IOException {

//		this.shp = new ShpOptions(shpFilePath, "EPSG:4326", StandardCharsets.ISO_8859_1);
//		this.shp = new ShpOptions(shpFilePath, "EPSG:25832", StandardCharsets.ISO_8859_1);
		// would be far better to get the CRS directly out of the shapefile.
		// Is actually possible, as follows:
		this.shp = new ShpOptions(shpFilePath, null, StandardCharsets.ISO_8859_1);

		// Reading shapefile from URL may not work properly, therefore, users may need to download the shape file to the local directory
		this.landUse = landUse;
		this.network = network;
		prepareMapping();
	}

	private void prepareMapping() throws IOException {
		logger.info("Reading NUTS shape files...");
		Set<String> relevantNutsIds = new HashSet<>();
		try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.resolveFileOrResource(lookUpTablePath), StandardCharsets.UTF_8)) {
			CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader()
				.setSkipHeaderRecord(true).build().parse(reader);
			for (CSVRecord record : parser) {
				if (!record.get(3).isEmpty()) {
					relevantNutsIds.add(record.get(3));
				}
			}
		}


		ShpOptions.Index shpIndex = shp.createIndex("EPSG:25832", "NUTS_ID", ft -> relevantNutsIds.contains(Objects.toString(ft.getAttribute("NUTS_ID"))));
		// This creates an index.  One will be able to put in coordinates, and get out NUTS_IDs.  Those NUTS_IDs can be filtered down to those which
		// are in relevantNutsIDs.  The query coordinate system is given.  The index will automatically transform it to the coordinate system of the
		// shapefile, and then do the query.  What comes back is an attribute value, which has nothing to do with a coordinate system.

		ShpOptions.Index landIndex = null;
		if (landUse != null) {
			logger.info("Reading land use data...");
			landIndex = landUse.getIndex("EPSG:25832");
			//TODO check if the land use reader functions properly
		}

		logger.info("Processing shape network and shapefile...");
		List<Link> links = network.getLinks().values().stream().filter(l -> l.getAllowedModes().contains("car"))
			.collect(Collectors.toList());
		Map<String, List<Link>> nutsToLinksMapping = new HashMap<>();
		Map<String, List<Link>> filteredNutsToLinksMapping = new HashMap<>();
		for (Link link : links) {
			String nutsId = shpIndex.query(link.getToNode().getCoord());
			if (nutsId != null) {
				nutsToLinksMapping.computeIfAbsent(nutsId, l -> new ArrayList<>()).add(link);
				if (landIndex != null) {
					if (!landIndex.contains(link.getToNode().getCoord())) {
						continue;
					}
					filteredNutsToLinksMapping.computeIfAbsent(nutsId, l -> new ArrayList<>()).add(link);
				}
			}
		}

		// When the filtered links list is not empty, then we use the filtered links list. Otherwise, we use the full link lists in the NUTS region.
		for (String nutsId : filteredNutsToLinksMapping.keySet()) {
			nutsToLinksMapping.put(nutsId, filteredNutsToLinksMapping.get(nutsId));
		}
		logger.info("Network and shapefile processing complete!");

		logger.info("Computing mapping between Verkehrszelle and departure location...");
		try (BufferedReader reader = IOUtils.getBufferedReader(IOUtils.resolveFileOrResource(lookUpTablePath), StandardCharsets.UTF_8)) {
			CSVParser parser = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setHeader()
				.setSkipHeaderRecord(true).build().parse(reader);
			for (CSVRecord record : parser) {
				String verkehrszelle = record.get(0);
				String nuts2021 = record.get(3);
				if (!nuts2021.isEmpty() && nutsToLinksMapping.get(nuts2021) != null) {
					mapping.put(verkehrszelle, nutsToLinksMapping.get(nuts2021).stream().map(Identifiable::getId).collect(Collectors.toList()));
					continue;
				}
				// For some international location (i,e, not neighboring countries of Germany), no suitable NUTS code is present.
				// In that case, we use the backup coord in the look-up table
				Coord backupCoord = new Coord(Double.parseDouble(record.get(5)), Double.parseDouble(record.get(6)));
				CoordinateTransformation ct = new GeotoolsTransformation("EPSG:4326", "EPSG:25832");
				Coord transformedCoord = ct.transform(backupCoord);
				Link backupLink = NetworkUtils.getNearestLink(network, transformedCoord);
				assert backupLink != null : "link closest to " + transformedCoord + "is null";
				mapping.put(verkehrszelle, List.of(backupLink.getId()));
			}
		}
		logger.info("Location generator is successfully created!");
	}

	@Override
	public Id<Link> getLocationOnNetwork(String verkehrszelle) {
		int size = mapping.get(verkehrszelle).size();
		return mapping.get(verkehrszelle).get(rnd.nextInt(size));
	}
}
