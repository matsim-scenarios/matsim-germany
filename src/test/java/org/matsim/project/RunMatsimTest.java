/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
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
package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.testcases.MatsimTestUtils;

/**
 * @author nagel
 *
 */
public class RunMatsimTest{
	private static final Logger log = LogManager.getLogger(RunMatsimTest.class);

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils() ;

	@Test
	public final void test() {
		try {
			String [] args = {"scenarios/equil/config.xml",
				  "--config:controller.outputDirectory", utils.getOutputDirectory(),
				  "--config:controller.lastIteration=1",
				  "--config:controller.writeEventsInterval=1"
			} ;
			RunMatsim.main( args ) ;
		} catch ( Exception ee ) {
            log.fatal("there was an exception: \n{}", String.valueOf(ee));

			// if one catches an exception, then one needs to explicitly fail the test:
			Assertions.fail();
		}


	}

}
