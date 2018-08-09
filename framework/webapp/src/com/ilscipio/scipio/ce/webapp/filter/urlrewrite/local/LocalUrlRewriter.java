package com.ilscipio.scipio.ce.webapp.filter.urlrewrite.local;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.webapp.FullWebappInfo;
import org.ofbiz.webapp.renderer.RenderEnvType;
import org.tuckey.web.filters.urlrewrite.Conf;
import org.tuckey.web.filters.urlrewrite.UrlRewriteWrappedResponse;
import org.tuckey.web.filters.urlrewrite.UrlRewriter;

import com.ilscipio.scipio.ce.webapp.filter.UrlFilterHelper;
import com.ilscipio.scipio.ce.webapp.filter.urlrewrite.ScipioUrlRewriter;
import com.ilscipio.scipio.ce.webapp.filter.urlrewrite.UrlConfUtil;

/**
 * SCIPIO: URL rewriter that invokes Tuckey using an emulated
 * servlet.
 * <p>
 * Added 2018-08.
 */
public class LocalUrlRewriter extends ScipioUrlRewriter {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    protected final String urlConfPath;
    protected final Conf conf;

    // emulated servlet API
    protected final LocalServletContainer container;

    // tuckey objects
    protected final UrlRewriter tuckRewriter;
    protected final UrlRewriteWrappedResponse tuckResponse;

    protected LocalUrlRewriter(String urlConfPath, Conf conf, LocalServletContainer container) throws IOException {
        this.urlConfPath = urlConfPath;
        this.conf = conf;
        this.container = container;
        this.tuckRewriter = new UrlRewriter(conf);
        this.tuckResponse = new UrlRewriteWrappedResponse(this.container.getResponse(),
                this.container.getRequest(), this.tuckRewriter);
    }

    public String getUrlConfPath() {
        return urlConfPath;
    }

    public Conf getConf() {
        return conf;
    }

    /**
     * @return the container
     */
    public LocalServletContainer getContainer() {
        return container;
    }

    public static class LocalFactory implements ScipioUrlRewriter.UrlRewriterFactory {

        //private static final Set<String> srcReqAttrToSkip = UtilMisc.unmodifiableHashSet("locale", "delegator",
        //        "dispatcher", "security", "timeZone", "servletContext", "_CONTEXT_ROOT_", "_SERVER_ROOT_URL_",
        //        "_CONTROL_PATH_", "_REQUEST_HANDLER_");

        @Override
        public LocalUrlRewriter loadForRequest(FullWebappInfo webappInfo, String urlConfPath,
                HttpServletRequest request, HttpServletResponse response) throws IOException {

            // re-emulate context here, backward - easier than making a ton of overloads
            Map<String, Object> context = new HashMap<>();

            context.put("locale", UtilHttp.getLocaleExistingSession(request));
            context.put("delegator", request.getAttribute("delegator"));
            context.put("dispatcher", request.getAttribute("dispatcher"));
            context.put("security", request.getAttribute("security"));
            context.put("timeZone", request.getAttribute("timeZone"));
            HttpSession session = request.getSession(false);
            if (session != null) {
                context.put("userLogin", session.getAttribute("userLogin"));
            }

            LocalUrlRewriter rewriter = loadForContext(webappInfo, urlConfPath, context, null);

            // now done at processOutboundUrl call due to circular references: 
            // orig request contains cache of rewriters, rewriters holding orig request reference...
            //rewriter.getContainer().getRequest().setAttribute(UrlFilterHelper.SOURCE_REQUEST, request);

            return rewriter;
        }

        @Override
        public LocalUrlRewriter loadForContext(FullWebappInfo webappInfo, String urlConfPath,
                Map<String, Object> context) throws IOException {
            LocalUrlRewriter rewriter = loadForContext(webappInfo, urlConfPath, context, null);

            // TODO: REVIEW: potential issue: circular reference: context contains
            // cache of rewriters, rewriter contains reference to context...
            // however, then we have to make copy of context and globalContext, which becomes expensive...
            rewriter.getContainer().getRequest().setAttribute(UrlFilterHelper.SOURCE_CONTEXT, context);

            return rewriter;
        }

        protected LocalUrlRewriter loadForContext(FullWebappInfo webappInfo, String urlConfPath,
                Map<String, Object> context, Map<String, Object> reqAttribs) throws IOException {
            Conf conf = UrlConfUtil.getConfFromLocationOrWebapp(urlConfPath, webappInfo.getExtWebappInfo());
            
            LocalUrlRewriter rewriter = new LocalUrlRewriter(urlConfPath, conf,
                    LocalServletContainer.fromOfbizContext(webappInfo, context, RenderEnvType.fromContext(context), reqAttribs));

            return rewriter;
        }
    }

    //@Override
    protected String processOutboundUrl(String url) {
        if (Debug.verboseOn()) {
            Debug.logVerbose("urlrewrite: processing outbound url for " + url, module);
        }
        try {
            return tuckResponse.encodeURL(url);
        } catch(Exception e) {
            Debug.logError(e, "Error encoding url '"
                    + url + "' using outbound-rules from " + urlConfPath + ": " + e.getMessage(), module);
            return url;
        }
    }

    @Override
    public String processOutboundUrl(String url, Map<String, Object> context) {
        // TODO: REVIEW: I wish we didn't have to set/unset the context at every process call,
        // but there is a risk of circular reference causing issues due to the context holding
        // caching containing our LocalUrlRewriter instance, so we can't keep reference to context safely?...
        try {
            getContainer().getRequest().setAttribute(UrlFilterHelper.SOURCE_CONTEXT, context);
            return processOutboundUrl(url);
        } finally {
            getContainer().getRequest().removeAttribute(UrlFilterHelper.SOURCE_CONTEXT);
        }
    }

    @Override
    public String processOutboundUrl(String url, HttpServletRequest request, HttpServletResponse response) {
        // TODO: REVIEW: I wish we didn't have to set/unset the request at every process call,
        // but there is a risk of circular reference causing issues due to the request holding
        // caching containing our LocalUrlRewriter instance, so we can't keep reference to request safely?...
        try {
            getContainer().getRequest().setAttribute(UrlFilterHelper.SOURCE_REQUEST, request);
            return processOutboundUrl(url);
        } finally {
            getContainer().getRequest().removeAttribute(UrlFilterHelper.SOURCE_REQUEST);
        }
    }
}
