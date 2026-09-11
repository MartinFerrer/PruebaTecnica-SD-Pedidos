import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Cross-platform verification entrypoint; it only requires the JDK. */
public final class Verify {

	private static final Set<String> SUITES = Set.of("quick", "full", "contracts", "acceptance",
			"concurrency", "property", "fuzz", "constrained", "chaos", "clean");

	private static final Pattern SECRET = Pattern.compile(
			"(?i)(ORDER_DB_PASSWORD|INVENTORY_DB_PASSWORD|RABBITMQ_PASSWORD|PASSWORD|TOKEN|SECRET)=([^\\s]+)");

	private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);

	private final Path repository;

	private final String suite;

	private final String seed;

	private final Path reportDirectory;

	private final Path logFile;

	private final Path metadataFile;

	private final Path resultFile;

	private final Map<String, Object> metadata = new LinkedHashMap<>();

	private boolean success = true;

	private Verify(String suite, String seed) throws IOException {
		this.repository = findRepository();
		this.suite = suite;
		this.seed = seed;
		String runId = RUN_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
		this.reportDirectory = repository.resolve("reports").resolve("verification").resolve(suite).resolve(runId);
		this.logFile = reportDirectory.resolve("run.log");
		this.metadataFile = reportDirectory.resolve("metadata.json");
		this.resultFile = reportDirectory.resolve("result.json");
		Files.createDirectories(reportDirectory);
		metadata.put("suite", suite);
		metadata.put("runId", runId);
		metadata.put("startedAt", Instant.now().toString());
		metadata.put("seed", seed);
		metadata.put("repository", repository.toString());
		metadata.put("gitSha", "");
		metadata.put("environment", new LinkedHashMap<>());
		metadata.put("credentialsPresent", new LinkedHashMap<>());
		Map<String, Object> configuration = new LinkedHashMap<>();
		configuration.put("composeFiles", List.of("compose.yaml"));
		configuration.put("overrides", List.of());
		configuration.put("failureInjection", seed == null ? "none" : "seed:" + seed);
		configuration.put("resourceLimits", "not-applied");
		metadata.put("configuration", configuration);
		metadata.put("images", List.of());
		metadata.put("steps", new ArrayList<>());
		metadata.put("reports", new LinkedHashMap<>());
		writeMetadata();
	}

	public static void main(String[] arguments) throws Exception {
		if (arguments.length == 0 || !SUITES.contains(arguments[0])) {
			System.err.println("Usage: java scripts/Verify.java <suite> [--seed value]");
			System.err.println("Suites: " + String.join(", ", SUITES));
			System.exit(2);
		}
		String seed = null;
		if (arguments.length == 3 && "--seed".equals(arguments[1])) {
			seed = arguments[2];
		}
		else if (arguments.length != 1) {
			System.err.println("Only --seed <value> is supported after the suite.");
			System.exit(2);
		}
		Verify verification = new Verify(arguments[0], seed);
		verification.initializeMetadata();
		try {
			verification.runSuite();
		}
		catch (Exception exception) {
			verification.success = false;
			verification.metadata.computeIfAbsent("runnerErrors", ignored -> new ArrayList<>());
			@SuppressWarnings("unchecked")
			List<String> errors = (List<String>) verification.metadata.get("runnerErrors");
			errors.add(exception.toString());
			verification.log("RUNNER_ERROR: " + exception + System.lineSeparator());
		}
		verification.finish();
		System.exit(verification.success ? 0 : 1);
	}

	private void initializeMetadata() throws IOException {
		metadata.put("gitSha", probe(List.of("git", "rev-parse", "HEAD")));
		@SuppressWarnings("unchecked")
		Map<String, Object> environment = (Map<String, Object>) metadata.get("environment");
		environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
		environment.put("java", probe(List.of("java", "-version")));
		environment.put("maven", probe(mavenCommand(List.of("--version"))));
		environment.put("docker", probe(List.of("docker", "version", "--format", "{{.Server.Version}}")));
		environment.put("dockerCompose", probe(List.of("docker", "compose", "version")));
		metadata.put("images", List.of(probe(List.of("docker", "compose", "images"))));
		@SuppressWarnings("unchecked")
		Map<String, Object> credentials = (Map<String, Object>) metadata.get("credentialsPresent");
		for (String name : List.of("ORDER_DB_PASSWORD", "INVENTORY_DB_PASSWORD", "RABBITMQ_PASSWORD")) {
			credentials.put(name, System.getenv(name) != null && !System.getenv(name).isBlank());
		}
		if (suite.equals("constrained")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> configuration = (Map<String, Object>) metadata.get("configuration");
			configuration.put("overrides", List.of("deploy/compose/constrained.yaml"));
			configuration.put("resourceLimits", "deploy/compose/constrained.yaml; effective inspection pending");
		}
		writeMetadata();
	}

	private void runSuite() throws IOException {
		switch (suite) {
			case "quick" -> runMaven(List.of("test"));
			case "full" -> runMaven(List.of("clean", "verify"));
			case "contracts" -> runMaven(List.of("-pl", "services/shared-library", "-am", "-Dtest=ContractTest", "test"));
			case "concurrency" -> runMaven(List.of("-pl", "services/order-service,services/inventory-service", "-am",
					"-Dtest=*Test", "-Dit.test=ServiceIT", "verify"));
			case "acceptance" -> runAcceptance();
			case "clean" -> runClean();
			case "property" -> markPending("No existe todavía un perfil de property tests");
			case "fuzz" -> markPending("No existe todavía un perfil de fuzzing con semillas/corpus");
			case "constrained" -> runConstrained();
			case "chaos" -> markPending("No existe todavía un override/arnés de caos de red");
			default -> throw new IllegalStateException("Unsupported suite: " + suite);
		}
	}

	private void runMaven(List<String> arguments) throws IOException {
		clearMavenReports();
		List<String> command = mavenCommand(arguments);
		runStep("maven", command, false);
	}

	private void runClean() throws IOException {
		if (commandAvailable("docker")) {
			runStep("compose-down", List.of("docker", "compose", "down"), true);
		}
		runMaven(List.of("clean"));
	}

	private void runConstrained() throws IOException {
		if (commandAvailable("docker")) {
			runStep("constrained-config", List.of("docker", "compose", "-f", "compose.yaml", "-f",
					"deploy/compose/constrained.yaml", "config", "--quiet"), false);
		}
		markPending("El arnés de presión efectiva y su verificador todavía no están versionados");
	}

	private void runAcceptance() throws IOException {
		clearMavenReports();
		if (!commandAvailable("docker")) {
			markPending("Docker no está disponible para acceptance");
			return;
		}
		try {
			if (!runStep("compose-config", List.of("docker", "compose", "config", "--quiet"), false)) {
				return;
			}
			if (!runStep("compose-up", List.of("docker", "compose", "up", "--build", "--wait",
					"--wait-timeout", "180"), false)) {
				return;
			}
			metadata.put("images", List.of(probe(List.of("docker", "compose", "images"))));
			writeMetadata();
			runStep("demo-data-first", List.of("docker", "compose", "--profile", "demo-data", "run", "--build",
				"--rm", "demo-data"), false);
			runStep("demo-data-replay", List.of("docker", "compose", "--profile", "demo-data", "run", "--rm",
				"demo-data"), false);
		}
		finally {
			runStep("compose-logs", List.of("docker", "compose", "logs", "--no-color"), true);
			runStep("compose-status", List.of("docker", "compose", "ps", "-a"), true);
			runStep("compose-down", List.of("docker", "compose", "down"), true);
		}
	}

	private boolean runStep(String name, List<String> command, boolean continueOnFailure) throws IOException {
		log(System.lineSeparator() + "$ " + String.join(" ", command) + System.lineSeparator());
		boolean passed = false;
		String error = null;
		try {
			Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
			try (BufferedReader output = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = output.readLine()) != null) {
					log(line + System.lineSeparator());
				}
			}
			int exitCode = process.waitFor();
			passed = exitCode == 0;
			if (!passed) {
				error = "exit=" + exitCode;
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			error = "interrupted";
		}
		catch (IOException exception) {
			error = "unavailable: " + exception.getMessage();
			log(error + System.lineSeparator());
		}
		Map<String, Object> step = new LinkedHashMap<>();
		step.put("name", name);
		step.put("command", command);
		step.put("finishedAt", Instant.now().toString());
		step.put("passed", passed);
		step.put("error", error);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> steps = (List<Map<String, Object>>) metadata.get("steps");
		steps.add(step);
		if (!passed && !continueOnFailure) {
			success = false;
		}
		writeMetadata();
		return passed;
	}

	private void markPending(String reason) throws IOException {
		success = false;
		@SuppressWarnings("unchecked")
		List<String> pending = (List<String>) metadata.computeIfAbsent("pending", ignored -> new ArrayList<>());
		pending.add(reason);
		log("SUITE_NOT_IMPLEMENTED: " + reason + System.lineSeparator());
		writeMetadata();
	}

	private void finish() throws IOException {
		Map<String, Integer> summary = collectMavenReports();
		if (Set.of("quick", "full", "contracts", "concurrency").contains(suite)) {
			validateExpectedTests(summary);
		}
		metadata.put("finishedAt", Instant.now().toString());
		metadata.put("status", success ? "passed" : "failed");
		writeMetadata();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("suite", suite);
		result.put("runId", metadata.get("runId"));
		result.put("status", metadata.get("status"));
		result.put("report", reportDirectory.toString());
		Files.writeString(resultFile, json(result) + System.lineSeparator(), StandardCharsets.UTF_8);
		System.out.println("Verification report: " + reportDirectory);
	}

	private Map<String, Integer> collectMavenReports() throws IOException {
		Map<String, Integer> summary = new LinkedHashMap<>();
		summary.put("surefireTests", 0);
		summary.put("failsafeTests", 0);
		summary.put("skipped", 0);
		summary.put("failures", 0);
		summary.put("errors", 0);
		List<String> files = new ArrayList<>();
		Path services = repository.resolve("services");
		if (Files.exists(services)) {
			try (var paths = Files.walk(services)) {
				for (Path report : paths.filter(this::isMavenReport).toList()) {
					Path relative = repository.relativize(report);
					Path copy = reportDirectory.resolve("maven").resolve(relative);
					Files.createDirectories(copy.getParent());
					Files.copy(report, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					files.add(relative.toString().replace('\\', '/'));
					readReport(report, summary);
				}
			}
		}
		Map<String, Object> reportData = new LinkedHashMap<>();
		reportData.put("files", files);
		reportData.put("summary", summary);
		@SuppressWarnings("unchecked")
		Map<String, Object> reports = (Map<String, Object>) metadata.get("reports");
		reports.put("maven", reportData);
		return summary;
	}

	private void clearMavenReports() throws IOException {
		Path services = repository.resolve("services");
		if (!Files.exists(services)) {
			return;
		}
		try (var paths = Files.walk(services)) {
			for (Path report : paths.filter(this::isAnyMavenReport).toList()) {
				Files.deleteIfExists(report);
			}
		}
	}

	private boolean isAnyMavenReport(Path path) {
		String normalized = path.toString().replace('\\', '/');
		return Files.isRegularFile(path) && path.getFileName().toString().startsWith("TEST-")
				&& path.getFileName().toString().endsWith(".xml")
				&& (normalized.contains("/target/surefire-reports/") || normalized.contains("/target/failsafe-reports/"));
	}

	private boolean isMavenReport(Path path) {
		return isAnyMavenReport(path);
	}

	private void readReport(Path report, Map<String, Integer> summary) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			Element suiteElement = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
			int tests = integerAttribute(suiteElement, "tests");
			String parent = report.getParent().getFileName().toString();
			String testType = parent.startsWith("surefire") ? "surefireTests" : "failsafeTests";
			summary.put(testType, summary.get(testType) + tests);
			summary.put("skipped", summary.get("skipped") + integerAttribute(suiteElement, "skipped"));
			summary.put("failures", summary.get("failures") + integerAttribute(suiteElement, "failures"));
			summary.put("errors", summary.get("errors") + integerAttribute(suiteElement, "errors"));
		}
		catch (Exception exception) {
			try {
				log("REPORT_PARSE_WARNING: " + report + System.lineSeparator());
			}
			catch (IOException logException) {
				success = false;
			}
		}
	}

	private int integerAttribute(Element element, String attribute) {
		String value = element.getAttribute(attribute);
		return value.isBlank() ? 0 : Integer.parseInt(value);
	}

	private void validateExpectedTests(Map<String, Integer> summary) throws IOException {
		@SuppressWarnings("unchecked")
		Map<String, Object> mavenReports = (Map<String, Object>) ((Map<String, Object>) metadata.get("reports")).get("maven");
		@SuppressWarnings("unchecked")
		List<String> files = (List<String>) mavenReports.get("files");
		boolean expected = switch (suite) {
			case "quick" -> summary.get("surefireTests") > 0;
			case "full" -> summary.get("surefireTests") > 0 && summary.get("failsafeTests") > 0;
			case "contracts" -> files.stream().anyMatch(file -> file.contains("ContractTest"));
			case "concurrency" -> files.stream().anyMatch(file -> file.contains("ServiceIT"));
			default -> true;
		};
		if (!expected) {
			markPending("No se descubrieron las pruebas esperadas para esta suite");
		}
		if (summary.get("skipped") > 0) {
			markPending("Se detectaron " + summary.get("skipped") + " pruebas omitidas");
		}
		if (summary.get("failures") > 0 || summary.get("errors") > 0) {
			success = false;
		}
	}

	private List<String> mavenCommand(List<String> arguments) {
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		Path wrapper = repository.resolve(windows ? "mvnw.cmd" : "mvnw");
		String executable = Files.exists(wrapper) ? wrapper.toString() : (windows ? "mvn.cmd" : "mvn");
		if (!windows) {
			List<String> command = new ArrayList<>();
			if (Files.exists(wrapper) && !Files.isExecutable(wrapper)) {
				command.add("sh");
			}
			command.add(executable);
			command.addAll(arguments);
			return command;
		}
		StringBuilder commandLine = new StringBuilder(quoteWindows(executable));
		for (String argument : arguments) {
			commandLine.append(' ').append(quoteWindows(argument));
		}
		return List.of("cmd.exe", "/d", "/s", "/c", commandLine.toString());
	}

	private String quoteWindows(String value) {
		return value.contains(" ") ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
	}

	private boolean commandAvailable(String command) {
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		List<String> lookup = windows ? List.of("where.exe", command) : List.of("which", command);
		try {
			Process process = new ProcessBuilder(lookup).redirectErrorStream(true).start();
			process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
			return process.waitFor() == 0;
		}
		catch (IOException exception) {
			return false;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private String probe(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
			String output;
			try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				output = reader.lines().reduce("", (left, right) -> left + (left.isEmpty() ? "" : System.lineSeparator()) + right);
			}
			process.waitFor();
			return redact(output.isBlank() ? "exit=" + process.exitValue() : output);
		}
		catch (Exception exception) {
			return "unavailable: " + exception.getMessage();
		}
	}

	private void log(String value) throws IOException {
		String safe = redact(value);
		Files.writeString(logFile, safe, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		System.out.print(safe);
	}

	private void writeMetadata() throws IOException {
		Files.writeString(metadataFile, json(metadata) + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	private String redact(String value) {
		return SECRET.matcher(value == null ? "" : value).replaceAll("$1=[REDACTED]");
	}

	private static Path findRepository() {
		Path current = Path.of("").toAbsolutePath().normalize();
		while (current != null && !Files.exists(current.resolve("pom.xml"))) {
			current = current.getParent();
		}
		if (current == null) {
			throw new IllegalStateException("Repository root with pom.xml not found");
		}
		return current;
	}

	private static String json(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String string) {
			return quoteJson(string);
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Map<?, ?> map) {
			List<String> entries = new ArrayList<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				entries.add(quoteJson(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()));
			}
			return "{" + String.join(",", entries) + "}";
		}
		if (value instanceof Iterable<?> iterable) {
			List<String> values = new ArrayList<>();
			for (Object item : iterable) {
				values.add(json(item));
			}
			return "[" + String.join(",", values) + "]";
		}
		return quoteJson(String.valueOf(value));
	}

	private static String quoteJson(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r")
				.replace("\n", "\\n").replace("\t", "\\t") + "\"";
	}

}
