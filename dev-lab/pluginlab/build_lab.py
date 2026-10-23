"""Builds the lab plugin jars (HiderLab + LuckPerms/WorldEdit/CoreProtect stubs) with javac.

usage:  python build_lab.py <paper server dir> [output dir]
        (output dir defaults to <paper server dir>/plugins)
JDK: set JAVA_HOME to a JDK 25 (the Paper 26.1 API is compiled for Java 25).
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

here = os.path.dirname(os.path.abspath(__file__))
paper = os.path.abspath(sys.argv[1])
out_dir = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.join(paper, "plugins")
javac = os.path.join(os.environ["JAVA_HOME"], "bin", "javac") if "JAVA_HOME" in os.environ else "javac"

jars = glob.glob(os.path.join(paper, "libraries", "**", "*.jar"), recursive=True)
if not any("paper-api" in j for j in jars):
    sys.exit("no paper-api jar under " + paper + "/libraries - start the server once so it unpacks its libraries")
classes = tempfile.mkdtemp(prefix="pluginlab-")
srcs = glob.glob(os.path.join(here, "src", "dev", "prismlab", "*.java"))
subprocess.check_call([javac, "--release", "25", "-proc:none", "-cp", os.pathsep.join(jars), "-d", classes] + srcs)

os.makedirs(out_dir, exist_ok=True)
for yml in sorted(glob.glob(os.path.join(here, "plugins", "*.yml"))):
    name = os.path.splitext(os.path.basename(yml))[0]
    main = "HiderLab" if name == "hiderlab" else "StubPlugin"
    jar = os.path.join(out_dir, "lab-" + name + ".jar")
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(yml, "plugin.yml")
        z.write(os.path.join(classes, "dev", "prismlab", main + ".class"), "dev/prismlab/" + main + ".class")
    print("built", jar)
shutil.rmtree(classes, ignore_errors=True)
