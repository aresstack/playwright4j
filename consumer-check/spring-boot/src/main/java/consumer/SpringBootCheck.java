package consumer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Started with {@code java -jar} from the Spring Boot jar: playwright4j and GraalVM are nested jars
 * loaded by Spring Boot's class loader, java.class.path is only the outer jar. Launching a browser
 * proves the driver JVM found its classpath anyway (audit H4).
 */
public final class SpringBootCheck {

    public static void main(String[] args) {
        String channel = System.getProperty("channel", "chrome");
        System.out.println("java.class.path=" + System.getProperty("java.class.path"));
        System.out.println("class loader=" + SpringBootCheck.class.getClassLoader().getClass().getName());
        try (Playwright playwright = Playwright.create()) {
            BrowserType type = channel.startsWith("moz-") ? playwright.firefox() : playwright.chromium();
            try (Browser browser = type.launch(new BrowserType.LaunchOptions().setChannel(channel).setHeadless(true))) {
                Page page = browser.newPage();
                page.setContent("<title>playwright4j-spring-boot</title><p>ok</p>");
                String title = page.title();
                if (!"playwright4j-spring-boot".equals(title)) {
                    throw new IllegalStateException("Unexpected title: " + title);
                }
                System.out.println("OK: " + channel + " " + browser.version() + " launched from a Spring Boot fat jar");
            }
        }
    }
}
