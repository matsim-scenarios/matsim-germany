Notes:
* Some of the classes in this package are in principle set up to be used via the (CR) Makefile approach.  However, everything is used in Java from GenerateFreightPlans.  For that reason, the above classes are set to non-public.  Could be changed if someone needs it otherwise.
* There are lots of hardcoded things in this whole package.  They should be sorted out and centralized.
       In particular, the coordinate transforms are hardcoded, instead of beging centralized and/or taken from files.  In particular, they are not
       taken from the shp files.

