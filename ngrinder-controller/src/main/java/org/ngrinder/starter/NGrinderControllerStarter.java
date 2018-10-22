/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.starter;

import com.beust.jcommander.*;
import net.grinder.lang.Lang;
import org.apache.catalina.Context;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.commons.lang.StringUtils;
import org.ngrinder.infra.config.ServletFilterConfig;
import org.ngrinder.infra.config.SpringConfig;
import org.ngrinder.infra.config.TaskConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedWebappClassLoader;
import org.springframework.boot.web.support.SpringBootServletInitializer;
import org.springframework.context.annotation.*;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.context.ContextLoaderListener;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.ProtectionDomain;
import java.util.*;

import static net.grinder.util.NoOp.noOp;

@SpringBootApplication
@ImportResource("classpath:applicationContext.xml")
@Import({SpringConfig.class, ServletFilterConfig.class})
@ComponentScan(
	basePackages = {"org.ngrinder"},
	excludeFilters = {@ComponentScan.Filter(type = FilterType.ANNOTATION, value = org.springframework.stereotype.Controller.class)}
)
@Parameters(separators = "= ")
public class NGrinderControllerStarter extends SpringBootServletInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(NGrinderControllerStarter.class);

	@Parameters(separators = "= ")
	enum ClusterMode {
		none {
			@Parameter(names = {"-cp", "--controller-port"}, description = "controller port for agent connection",
				validateValueWith = PortAvailabilityValidator.class)
			private Integer controllerPort = 16001;

			public void process() {
				if (controllerPort != null) {
					System.setProperty("controller.controller_port", controllerPort.toString());
				}
			}
		},
		easy {
			@Parameter(names = {"-clh", "--cluster-host"},
				description = "This cluster member's cluster communication host. The default value is the " +
					"first non-localhost address. if it's localhost, " +
					"it can only communicate with the other cluster members in the same machine.")
			private String clusterHost = null;

			@Parameter(names = {"-clp", "--cluster-port"}, required = true,
				description = "This cluster member's cluster communication port. Each cluster member should " +
					"be run with unique cluster port.",
				validateValueWith = PortAvailabilityValidator.class)
			private Integer clusterPort = null;

			@Parameter(names = {"-cp", "--controller-port"}, required = true,
				description = "This cluster member's agent connection port",
				validateValueWith = PortAvailabilityValidator.class)
			private Integer controllerPort = null;

			@Parameter(names = {"-r", "--region"}, required = true,
				description = "This cluster member's region name")
			private String region = null;


			@Parameter(names = {"-dh", "--database-host"},
				description = "database host. The default value is localhost")
			private String databaseHost = "localhost";

			@Parameter(names = {"-dp", "--database-port"},
				description = "database port. The default value is 9092 when h2 is used and " +
					"3306 when mysql is used."
			)
			private Integer databasePort = null;

			@Parameter(names = {"-dt", "--database-type"},
				description = "database type", hidden = true)
			private String databaseType = "h2";

			@SuppressWarnings("SpellCheckingInspection")
			public void process() {
				System.setProperty("cluster.mode", "easy");
				if (clusterHost != null) {
					System.setProperty("cluster.host", clusterHost);
				}
				System.setProperty("cluster.port", clusterPort.toString());
				System.setProperty("cluster.region", region);
				System.setProperty("controller.controller_port", controllerPort.toString());
				System.setProperty("database.type", databaseType);
				if ("h2".equals(databaseType)) {
					if (databasePort == null) {
						databasePort = 9092;
					}
					if (!tryConnection(databaseHost, databasePort)) {
						throw new ParameterException("Failed to connect h2 db " + databaseHost + ":" + databasePort
							+ ".\nPlease run the h2 TcpServer in advance\n"
							+ "or set the correct -database-host and -database-port parameters");
					}
					System.setProperty("database.url", "tcp://" + this.databaseHost + ":" + databasePort + "/db/ngrinder");
				} else {
					if (databasePort == null) {
						databasePort = 3306;
					}
					if (!tryConnection(databaseHost, databasePort)) {
						throw new ParameterException("Failed to connect mysql db.\n" +
							"Please run the mysql db " + databaseHost + ":" + databasePort + "in advance\n" +
							"or set the correct -database-host and -database-port parameters");
					}
					System.setProperty("database.url", this.databaseHost + ":" + this.databasePort);
				}
			}

		},
		advanced {
			public void process() {
				System.setProperty("cluster.mode", "advanced");
			}
		};

		@SuppressWarnings("Duplicates")
		public void parseArgs(String[] args) {
			JCommander commander = new JCommander(ClusterMode.this);
			String clusterModeOption = "";
			if (this != ClusterMode.none) {
				clusterModeOption = " --cluster-mode=" + name();
			}
			commander.setProgramName(getRunningCommand() + clusterModeOption);
			try {
				commander.parse(args);
				process();
			} catch (Exception e) {
				System.err.println("[Configuration Error]");
				System.err.println(e.getMessage());
				commander.usage();
				System.exit(-1);
			}
		}

		abstract void process();
	}

	private static boolean tryConnection(String byConnecting, int port) {
		Socket socket = null;
		try {
			socket = new Socket();
			socket.connect(new InetSocketAddress(byConnecting, port), 2000); // 2 seconds timeout
		} catch (Exception e) {
			return false;
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (Exception e) {
					noOp();
				}
			}
		}
		return true;
	}

	@Parameter(names = {"-p", "--port"}, description = "HTTP port of the server. The default port is 8080.",
		validateValueWith = PortAvailabilityValidator.class)
	private Integer port = null;

	@Parameter(names = {"-c", "--context-path"}, description = "context path of the embedded web application.")
	private String contextPath = "/";

	@Parameter(names = {"-cm", "--cluster-mode"}, description = "cluster-mode can be easy or advanced  ")
	private String clusterMode = "none";

	@Parameter(names = {"-nh", "--ngrinder-home"}, description = "nGridner home directory")
	private String home = null;

	@SuppressWarnings("SpellCheckingInspection")
	@Parameter(names = {"-exh", "--ex-home"}, description = "nGridner extended home directory")
	private String exHome = null;

	@Parameter(names = {"-help", "-?", "-h"}, description = "prints this message")
	private Boolean help = false;


	@SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
	@DynamicParameter(names = "-D", description = "Dynamic parameters", hidden = true)
	private Map<String, String> params = new HashMap<>();


	public static boolean isEmpty(String str) {
		return str == null || str.length() == 0;
	}

	private static String getWarName() {
		ProtectionDomain protectionDomain = NGrinderControllerStarter.class.getProtectionDomain();
		String warName = protectionDomain.getCodeSource().getLocation().toExternalForm();
		if (warName.endsWith("/classes/")) {
			warName = "ngrinder-controller-X.X.war";
		}
		return warName;
	}


	public static void main(String[] args) throws Exception {
		NGrinderControllerStarter server = new NGrinderControllerStarter();
		JCommander commander = new JCommander(server);
		commander.setAcceptUnknownOptions(true);
		commander.setProgramName("ngrinder");
		PortAvailabilityValidator validator = new PortAvailabilityValidator();
		try {
			commander.parse(args);
			if (server.port == null) {
				server.port = 8080;
			}
			validator.validate("-p / --port", server.port);
		} catch (Exception e) {
			System.err.println(e.getMessage());
			commander.usage();
			System.exit(0);
		}
		System.setProperty("server.port", Integer.toString(server.port));

		if (!server.contextPath.startsWith("/")) {
			server.contextPath = "/" + server.contextPath;
		}
		System.setProperty("server.contextPath", server.contextPath);

		if (server.help) {
			commander.usage();
			System.exit(0);
		}

		if (server.home != null) {
			System.setProperty("ngrinder.home", server.home);
		}
		if (server.exHome != null) {
			System.setProperty("ngrinder.ex.home", server.exHome);
		}
		final List<String> unknownOptions = commander.getUnknownOptions();
		final ClusterMode clusterMode = ClusterMode.valueOf(server.clusterMode);
		clusterMode.parseArgs(unknownOptions.toArray(new String[unknownOptions.size()]));
		System.getProperties().putAll(server.params);
		// copyRequiredLibraryForValidation();
		SpringApplication.run(NGrinderControllerStarter.class, args);
	}

	private static String getRunningCommand() {
		return "java -jar " + new File(getWarName()).getName();
	}

	protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
		return application.sources(NGrinderControllerStarter.class);
	}

/*	private static void copyRequiredLibraryForValidation() throws IOException {
		Set<String> classPathSet = new HashSet<>();
		StringBuilder classPaths = new StringBuilder();
		Arrays.stream(Lang.values()).forEach(l -> classPaths.append(l.getHandler().getClassPathProcessor().buildForemostClasspathBasedOnCurrentClassLoader(LOGGER)).append(File.pathSeparator)
			.append(l.getHandler().getClassPathProcessor().buildPatchClasspathBasedOnCurrentClassLoader(LOGGER)).append(File.pathSeparator)
			.append(l.getHandler().getClassPathProcessor().buildClasspathBasedOnCurrentClassLoader(LOGGER)));
		Arrays.stream(classPaths.toString().split(File.pathSeparator)).filter(StringUtils::isNotEmpty).forEach(path -> classPathSet.add(FilenameUtils.getName(path)));
		copyLibs(classPathSet);
	}

	private static void copyLibs(Set classPathSet) throws IOException {
		Path requiredLibraryDirectory = new File(Config.getUserHome(), "libs").toPath();
		FileUtils.deleteDirectory(new File(requiredLibraryDirectory.toString()));
		if (getWarName().contains(".war!")) {
			FileSystem zipFs = FileSystems.newFileSystem(getWarFileURI(), new HashMap<>());
			Path pathInZip = zipFs.getPath("/WEB-INF", "lib");
			Files.createDirectories(requiredLibraryDirectory);
			Files.walkFileTree(pathInZip, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path filePath, BasicFileAttributes attrs) throws IOException {
					// Make sure that we conserve the hierachy of files and folders inside the zip
					Path relativePathInZip = pathInZip.relativize(filePath);
					if (classPathSet.contains(relativePathInZip.getFileName().toString())) {
						Path targetPath = requiredLibraryDirectory.resolve(relativePathInZip.toString());
						Files.copy(filePath, targetPath);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			List<Path> copyLibs = Arrays.stream(((URLClassLoader) NGrinderControllerStarter.class.getClassLoader()).getURLs())
				.filter(path -> classPathSet.contains(FilenameUtils.getName(path.getFile())))
				.map(path -> new File(path.getFile()).toPath()).collect(Collectors.toList());
			Files.createDirectories(requiredLibraryDirectory);
			for (Path lib : copyLibs) {
				Path path = Paths.get(requiredLibraryDirectory.toString(), lib.getFileName().toString());
				if (Files.exists(path)) {
					Files.copy(lib, path);
				}
			}
		}
	}

	private static URI getWarFileURI() {
		String warFilePath = ((URLClassLoader) AbstractGrinderClassPathProcessor.class.getClassLoader()).getURLs()[0].getPath();
		return URI.create("jar:" + warFilePath.substring(0, warFilePath.indexOf(".war!") + ".war".length()));
	}*/

	@Override
	public void onStartup(ServletContext servletContext)
		throws ServletException {
		servletContext.setInitParameter(
			"spring.profiles.default", "production");
		servletContext.addListener(new ContextLoaderListener());
		servletContext.addListener(new HttpSessionEventPublisher());
		super.onStartup(servletContext);
	}

	@Bean
	public EmbeddedServletContainerFactory servletContainer() {
		return new TomcatEmbeddedServletContainerFactory() {
			@Override
			protected void postProcessContext(Context context) {
				/*
				 * This is the custom class loader to remove duplicated hazelcast ServiceLoader issue.
				 *
				 * @see https://github.com/hazelcast/hazelcast/issues/2395#issuecomment-245461396
				 */
				WebappLoader loader = new WebappLoader(context.getParentClassLoader());
				loader.setLoaderClass(TomcatEmbeddedWebappClassLoader.class.getName());
				loader.setDelegate(true);
				context.setLoader(loader);
				StandardRoot standardRoot = new StandardRoot(context);
				context.setResources(standardRoot);
			}
		};
	}
}