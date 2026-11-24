# Cloud Nav third-party notices

## GeoNames (CC BY 4.0)

The offline gazetteer `app/src/main/assets/geo/gazetteer.tsv` (city name, country, coordinates and IANA time zone used to
name visits and pick the sleep city's clock offline, #1887) and the test slice `app/src/test/resources/kml/gazetteer.tsv`
are derived from the GeoNames `cities15000` dump, reduced by `test/gen-gazetteer.py`.

Attribution: "GeoNames, https://www.geonames.org, CC BY 4.0" (https://creativecommons.org/licenses/by/4.0/).
Changes: columns reduced to name, country name, latitude, longitude and time zone; no other edits.
