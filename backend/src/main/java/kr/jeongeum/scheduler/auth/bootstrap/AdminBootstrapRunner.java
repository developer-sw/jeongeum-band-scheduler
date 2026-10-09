package kr.jeongeum.scheduler.auth.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("bootstrap-admin")
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapService bootstrap;
    private final ConfigurableApplicationContext context;

    public AdminBootstrapRunner(AdminBootstrapService bootstrap, ConfigurableApplicationContext context) {
        this.bootstrap = bootstrap;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (context instanceof WebServerApplicationContext) {
            throw new IllegalStateException("Administrator initialization cannot run in a web application");
        }
        bootstrap.initialize(requiredEnvironment("APP_BOOTSTRAP_LOGIN_ID"),
                requiredEnvironment("APP_BOOTSTRAP_PASSWORD"), requiredEnvironment("APP_BOOTSTRAP_DISPLAY_NAME"));
        // No login identifier, name, password, or hash is printed.
        SpringApplication.exit(context, () -> 0);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required bootstrap process environment is missing");
        }
        return value;
    }
}
