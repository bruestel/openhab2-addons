package org.openhab.binding.homeconnect.internal.servlet;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServlet;

import org.apache.commons.io.IOUtils;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractServlet extends HttpServlet {

    private final static long serialVersionUID = 1L;

    protected final static String SERVLET_BASE_PATH = "/homeconnect";
    protected final static String CONTENT_TYPE = "text/html;charset=UTF-8";
    private final static String TEMPLATE_BASE_PATH = "/templates/";
    private final static Pattern MESSAGE_KEY_PATTERN = Pattern.compile("\\$\\{([^\\}]+)\\}");

    private final Logger logger = LoggerFactory.getLogger(AbstractServlet.class);
    protected final HttpService httpService;
    protected final BundleContext bundleContext;

    protected AbstractServlet(HttpService httpService, BundleContext bundleContext) {
        this.httpService = httpService;
        this.bundleContext = bundleContext;
    }

    protected String readHtmlTemplate(String htmlTemplate) throws IOException {
        final URL templateUrl = bundleContext.getBundle().getEntry(TEMPLATE_BASE_PATH + htmlTemplate);

        if (templateUrl == null) {
            throw new FileNotFoundException("Cannot find template file '" + htmlTemplate + "'.");
        }

        try (InputStream inputStream = templateUrl.openStream()) {
            return IOUtils.toString(inputStream);
        }
    }

    protected String replaceKeysFromMap(String template, Map<String, String> map) {
        final Matcher m = MESSAGE_KEY_PATTERN.matcher(template);
        final StringBuffer sb = new StringBuffer();

        while (m.find()) {
            try {
                final String key = m.group(1);
                m.appendReplacement(sb, Matcher.quoteReplacement(map.getOrDefault(key, "${" + key + '}')));
            } catch (RuntimeException e) {
                logger.debug("Error occurred during template filling, cause ", e);
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

}
