#!/usr/bin/env python3
"""Run real Kotlin client code against the exact Node VPS code on loopback.

Requires preinstalled Kotlin compiler distributions and project dependency jars.
Nothing connects to the live VPS, Simkl, or installed apps.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--tv-repo", type=Path, required=True)
parser.add_argument("--server-repo", type=Path, required=True)
parser.add_argument("--runtime", type=Path, required=True)
args = parser.parse_args()
here = Path(__file__).resolve().parent
mobile = here.parents[1]
runtime = args.runtime.resolve()

with tempfile.TemporaryDirectory(prefix="rewatch-clients-") as temp:
    work = Path(temp)
    clients = {}
    for platform in ("tv", "ipad"):
        if platform == "tv":
            package = "com.nuvio.tv.domain.rewatch"
            folder = args.tv_repo.resolve() / "app/src/main/java/com/nuvio/tv/domain/rewatch"
            compiler = runtime / "kotlinc"
            deps = runtime / "tv-common"
            jars = [deps / "coroutines.jar", deps / "serialization-core.jar",
                    deps / "serialization-json.jar", runtime / "gson.jar"]
        else:
            package = "com.nuvio.app.features.rewatch"
            folder = mobile / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/rewatch"
            compiler = runtime / "ios-common/kotlinc"
            jars = [runtime / "ios-common" / name for name in
                    ("coroutines.jar", "serialization-core.jar", "serialization-json.jar")]
        names = ["RewatchSessionPolicy", "RewatchCommittedEventPlanner", "RewatchSyncOutbox",
                 "RewatchRemoteCheckpointReader", "RewatchVpsApi", "RewatchSilentSimklBootstrap"]
        if platform == "ipad":
            names.append("RewatchLocalSyncLedger")
        sources = [str(folder / (name + ".kt")) for name in names]
        probe = work / (platform + "-probe.kt")
        probe.write_text((here / "ClientContractAudit.kt.template").read_text().replace("PACKAGE_NAME", package))
        jar = work / (platform + ".jar")
        cp = ":".join(map(str, jars))
        subprocess.run(["bash", str(compiler / "bin/kotlinc"), *sources, str(probe),
                        "-cp", cp, "-Xplugin=" + str(compiler / "lib/kotlinx-serialization-compiler-plugin.jar"),
                        "-d", str(jar)], check=True)
        clients[platform] = ["java", "-cp", cp + ":" + str(jar) + ":" + str(compiler / "lib/kotlin-stdlib.jar"),
                             package + "." + platform.capitalize() + "_probeKt"]
    fixture = subprocess.Popen(["node", str(here / "server-fixture.mjs"),
                                str(args.server_repo.resolve() / "services/rewatch-sync")],
                               stdout=subprocess.PIPE, text=True)
    try:
        state = json.loads(fixture.stdout.readline())
        assert state["revision"] == 666, state
        origin = state["origin"]
        subprocess.run(clients["ipad"] + [origin, "repair", "simkl-device:ipad-audit", "a", "PROGRESS", "16"], check=True)
        subprocess.run(clients["tv"] + [origin, "pull", "simkl-device:tv-audit", "b", "16", "34000"], check=True)
        subprocess.run(clients["tv"] + [origin, "repair", "simkl-device:tv-audit", "b", "REPOSITION", "15"], check=True)
        subprocess.run(clients["ipad"] + [origin, "pull", "simkl-device:ipad-audit", "a", "15", "34000"], check=True)
        print("BIDIRECTIONAL_KOTLIN_NODE_CONTRACT=PASS", flush=True)
    finally:
        fixture.terminate()
        fixture.wait(timeout=15)
