package com.ilscipio.scipio.ce.lang.reflect;

import org.ofbiz.base.util.cache.UtilCache;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;
import org.reflections.scanners.MemberUsageScanner;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.MethodParameterNamesScanner;
import org.reflections.scanners.MethodParameterScanner;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.scanners.TypeElementsScanner;
import org.reflections.util.ConfigurationBuilder;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Wrapper around org.reflections/org.reflections8 library for simplified use.
 * <p>DEV NOTE: This does not currently abstract Reflections; it's intended to add helper calls around expensive cached operations.</p>
 */
public class ReflectQuery {

    private static final UtilCache<String, ReflectQuery> URL_CACHE = UtilCache.createUtilCache("reflect.query");

    protected final Collection<URL> jarUrls;
    /**
     * The Reflections object, which acts as queryable cache for expensive operations.
     * <p>If a method is called which requires type scanners which were not specified at creation, it may be rebuilt.</p>
     */
    protected final Reflections reflections;

    protected ReflectQuery(Collection<URL> jarUrls) {
        this.jarUrls = jarUrls;
        this.reflections = new Reflections(new ConfigurationBuilder()
                .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner(), new MethodAnnotationsScanner())
                // TODO: REVIEW: could slow down loading:
                //  new FieldAnnotationsScanner(), new MemberUsageScanner(), new MethodParameterNamesScanner(),
                //                        , new ResourcesScanner(), new MethodParameterScanner(),
                //                        new TypeElementsScanner()
                .setUrls(jarUrls));
    }

    /*
     * Factory methods
     */

    public static ReflectQuery fromJarUrls(Collection<URL> jarUrls, boolean useCache) {
        if (!useCache) {
            return new ReflectQuery(jarUrls);
        }
        Set<String> orderedUrls = new TreeSet<>();
        for(URL url : jarUrls) {
            orderedUrls.add(url.toString());
        }
        String cacheKey = String.join("::", orderedUrls);
        ReflectQuery rq = URL_CACHE.get(cacheKey);
        if (rq == null) {
            rq = new ReflectQuery(jarUrls);
            rq = URL_CACHE.putIfAbsentAndGet(cacheKey, rq);
        }
        return rq;
    }

    public static ReflectQuery fromJarFiles(Collection<File> jarFiles, boolean useCache) {
        return fromJarUrls(getJarUrlsForFiles(jarFiles), useCache);
    }

    public Reflections getReflections() {
        return reflections;
    }

    public Collection<URL> getJarUrls() {
        return jarUrls;
    }

    /*
     * Queries
     */

    public Set<Class<?>> getAnnotatedClasses(Class<? extends Annotation> annotationCls) {
        return getReflections().getTypesAnnotatedWith(annotationCls);
    }

    public Set<Class<?>> getAnnotatedClasses(Collection<Class<? extends Annotation>> annotationClsList) {
        Set<Class<?>> allClasses = new LinkedHashSet<>();
        for(Class<? extends Annotation> annotationCls : annotationClsList) {
            Set<Class<?>> classes = getAnnotatedClasses(annotationCls);
            if (classes != null) {
                allClasses.addAll(classes);
            }
        }
        return allClasses;
    }

    public static List<URL> getJarUrlsForFiles(Collection<File> jarFiles) {
        return jarFiles.stream().map(file -> {
            try {
                return file.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }).collect(Collectors.toList());
    }
}
