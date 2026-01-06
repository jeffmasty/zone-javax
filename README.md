# zone-javax
JavaSound integration for the JudahZone project


zone-javax provides a compact, dependency-minimal JavaSound bridge that is particularly useful as a low-latency fallback or test harness when JACK is not available. Notably, it includes JavaxGraph — a small routing/graph helper that makes wiring and inspecting audio I/O nodes simple — plus lightweight input/output helpers (JavaxIn, JavaxOut, JavaxOutSimpler) so the core project can run and be tested on plain JVM desktop environments without native JACK.

Quick notes

	1. Project is a Maven module under the meta-zone aggregator; build from the parent for simplest results.
	2. Compiler is configured to run Lombok annotation processing (see pom.xml); 
	3. Core dependency: zone-core. Optional GUI integration via zone-gui.

Build (from parent)

mvn clean package

Build single module (from parent)

mvn -pl zone-javax -am clean package

Files of interest: JavaxGraph.java, JavaxIn.java, JavaxOut.java, JavaxOutSimpler.java — useful starting points for routing JavaSound I/O.