#!/usr/bin/env python3
"""Builds app/src/main/assets/cities.bin, the offline city list for world clocks
(gzipped TSV; named .bin because the Android build unpacks and renames .gz assets).

Source: GeoNames (https://www.geonames.org/), CC BY 4.0. Download and unzip
  https://download.geonames.org/export/dump/cities5000.zip
  https://download.geonames.org/export/dump/admin1CodesASCII.txt
then run:  python3 tools/make-cities.py cities5000.txt admin1CodesASCII.txt

Output, one city per line, largest population first:
  name <TAB> ascii name <TAB> country code <TAB> region <TAB> IANA time zone
"""
import gzip
import os
import sys

cities_path, admin1_path = sys.argv[1], sys.argv[2]
out_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "cities.bin")

regions = {}
with open(admin1_path, encoding="utf-8") as f:
    for line in f:
        code, name, *_ = line.rstrip("\n").split("\t")
        regions[code] = name

rows = []
with open(cities_path, encoding="utf-8") as f:
    for line in f:
        c = line.rstrip("\n").split("\t")
        name, ascii_name, country, admin1, population, tz = c[1], c[2], c[8], c[10], c[14], c[17]
        if not tz:
            continue
        region = regions.get(f"{country}.{admin1}", "")
        rows.append((int(population or 0), name, ascii_name, country, region, tz))

rows.sort(key=lambda r: -r[0])
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with gzip.open(out_path, "wt", encoding="utf-8", compresslevel=9) as out:
    for _, name, ascii_name, country, region, tz in rows:
        clean = [s.replace("\t", " ") for s in (name, ascii_name, country, region, tz)]
        out.write("\t".join(clean) + "\n")
print(f"{len(rows)} cities -> {out_path} ({os.path.getsize(out_path) // 1024} KB)")
