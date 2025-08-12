Notes:
* Some of the classes in this package are in principle set up to be used via the (CR) Makefile approach.  However, everything is used in Java from GenerateFreightPlans.  For that reason, the above classes are set to non-public.  Could be changed if someone needs it otherwise.
* There are lots of hardcoded things in this whole package.  They should be sorted out and centralized.
       In particular, the coordinate transforms are hardcoded, instead of beging centralized and/or taken from files.  In particular, they are not
       taken from the shp files.
* for the old Verflechtungsprognose we had no shape file for the zones of the data source, --> create the LookupTable for the zones from the data source.
* If we want to use a shapefile for the German zones we could use a shape file of the "Kreise" in Germany, which is available at https://gdz.bkg.bund.de/index.php/default/open-data/verwaltungsgebiete-1-250-000-stand-01-01-vg250-01-01.html
* The new Verflechtungsprognose has a shape file for the zones of the data source, so we can use that and we do not need to create a LookupTable for the zones from the data source.

