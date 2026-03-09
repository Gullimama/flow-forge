# SPMF (Sequential Pattern Mining Framework)

SPMF is not published on Maven Central. To build the `pattern-mining` module:

1. Download the JAR from the official site:
   - https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php
   - Or direct: https://www.philippe-fournier-viger.com/spmf/spmf.jar

2. Save it as `spmf.jar` in this directory (`libs/spmf/spmf.jar`).

3. For CI, add a step before the build, e.g.:
   ```bash
   mkdir -p libs/spmf && curl -L -o libs/spmf/spmf.jar "https://www.philippe-fournier-viger.com/spmf/spmf.jar"
   ```

Version 2.60 or later is recommended (see `gradle/libs.versions.toml`).
