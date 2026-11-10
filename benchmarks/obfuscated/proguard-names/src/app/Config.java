package app;
import java.util.*;
public class Config {
    private final int port;
    private final String host;
    private final boolean debug;
    public Config(Properties props) {
        this.port = Integer.parseInt(props.getProperty("server_port"));
        this.host = props.getProperty("host");
        this.debug = Boolean.parseBoolean(props.getProperty("debug"));
    }
    private boolean isDebug() { return debug; }
    public String summary() { return host + ":" + port + isDebug(); }
}
