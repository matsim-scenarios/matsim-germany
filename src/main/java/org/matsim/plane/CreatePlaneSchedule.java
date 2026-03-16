package org.matsim.plane;

/**
 * This class provides a parser for the EUROCONTROL flight data and generates a MATSim schedule. <br/><br/>
 *
 * The <i>EUROCONTROL Aviation Data Repository for Research</i> datasets are available <a href="https://www.eurocontrol.int/dashboard/aviation-data-research"/>here<a/>.
 * This class only needs the basic <i>Flight</i> dataset. The data is free for Research and development purposes, updated every quarter and includes
 * all flights from a given month two years prior. <i>Example: The currently used dataset for 12/2023 was released in 01/2026.</i> <br/><br/>
 *
 * This class also generates a plane-network, containing airports and connection links which can be later fused with the given scenario network
 * using the {@link AirportNetworkFuser}.
 */
public class CreatePlaneSchedule {
	public static void main(String[] args) {

	}
}
