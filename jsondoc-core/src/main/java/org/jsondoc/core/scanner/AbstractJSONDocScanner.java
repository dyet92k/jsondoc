package org.jsondoc.core.scanner;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jsondoc.core.annotation.ApiAuthBasic;
import org.jsondoc.core.annotation.ApiAuthNone;
import org.jsondoc.core.annotation.ApiFlow;
import org.jsondoc.core.annotation.ApiFlowSet;
import org.jsondoc.core.pojo.ApiAuthDoc;
import org.jsondoc.core.pojo.ApiDoc;
import org.jsondoc.core.pojo.ApiErrorDoc;
import org.jsondoc.core.pojo.ApiFlowDoc;
import org.jsondoc.core.pojo.ApiHeaderDoc;
import org.jsondoc.core.pojo.ApiMethodDoc;
import org.jsondoc.core.pojo.ApiObjectDoc;
import org.jsondoc.core.pojo.ApiObjectFieldDoc;
import org.jsondoc.core.pojo.ApiParamDoc;
import org.jsondoc.core.pojo.ApiVerb;
import org.jsondoc.core.pojo.ApiVersionDoc;
import org.jsondoc.core.pojo.JSONDoc;
import org.jsondoc.core.pojo.JSONDoc.MethodDisplay;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractJSONDocScanner implements JSONDocScanner {
	
	protected Reflections reflections = null;
	
	protected static Logger log = LoggerFactory.getLogger(JSONDocScanner.class);

	public abstract Set<Class<?>> jsondocControllers();
	public abstract Set<Method> jsondocMethods(Class<?> controller);
	public abstract Set<Class<?>> jsondocObjects();

	public abstract ApiDoc initApiDoc(Class<?> controller);
	public abstract ApiDoc mergeApiDoc(Class<?> controller, ApiDoc apiDoc);

	public abstract ApiMethodDoc initApiMethodDoc(Method method, Class<?> controller);
	public abstract ApiMethodDoc mergeApiMethodDoc(Method method, Class<?> controller, ApiMethodDoc apiMethodDoc);
	
	public abstract ApiObjectDoc initApiObjectDoc(Class<?> clazz);
	public abstract ApiObjectDoc mergeApiObjectDoc(Class<?> clazz, ApiObjectDoc apiObjectDoc);
	
	protected List<ApiMethodDoc> allApiMethodDocs = new ArrayList<ApiMethodDoc>();
	
	protected Set<Class<?>> jsondocControllers = new LinkedHashSet<Class<?>>();
	protected Set<Method> jsondocMethods = new LinkedHashSet<Method>();
	protected Set<Class<?>> jsondocObjects = new LinkedHashSet<Class<?>>();
	
	/**
	 * Returns the main <code>ApiDoc</code>, containing <code>ApiMethodDoc</code> and <code>ApiObjectDoc</code> objects
	 * @return An <code>ApiDoc</code> object
	 */
	public JSONDoc getJSONDoc(String version, String basePath, List<String> packages, boolean playgroundEnabled, MethodDisplay displayMethodAs) {
		Set<URL> urls = new HashSet<URL>();
		FilterBuilder filter = new FilterBuilder();
		
		log.debug("Found " + packages.size() + " package(s) to scan...");
		for (String pkg : packages) {
			log.debug("Adding package to JSONDoc recursive scan: " + pkg);
			urls.addAll(ClasspathHelper.forPackage(pkg));
			filter.includePackage(pkg);
		}

		reflections = new Reflections(new ConfigurationBuilder().filterInputsBy(filter).setUrls(urls));
		
		JSONDoc jsondocDoc = new JSONDoc(version, basePath);
		
		jsondocControllers = jsondocControllers();
		jsondocDoc.setApis(getApiDocsMap(jsondocControllers, displayMethodAs));
		
		jsondocObjects = jsondocObjects();
		jsondocDoc.setObjects(getApiObjectsMap(jsondocObjects));
		jsondocDoc.setFlows(getApiFlowDocsMap(reflections.getTypesAnnotatedWith(ApiFlowSet.class, true), allApiMethodDocs));
		jsondocDoc.setPlaygroundEnabled(playgroundEnabled);
		jsondocDoc.setDisplayMethodAs(displayMethodAs);
		return jsondocDoc;
	}
	
	/**
	 * Gets the API documentation for the set of classes passed as argument
	 */
	public Set<ApiDoc> getApiDocs(Set<Class<?>> classes, MethodDisplay displayMethodAs) {
		Set<ApiDoc> apiDocs = new TreeSet<ApiDoc>();
		for (Class<?> controller : classes) {
			ApiDoc apiDoc = getApiDoc(controller, displayMethodAs);
			apiDocs.add(apiDoc);
		}
		return apiDocs;
	}
	
	/**
	 * Gets the API documentation for a single class annotated with @Api and for its methods, annotated with @ApiMethod
	 * @param controller
	 * @return
	 */
	private ApiDoc getApiDoc(Class<?> controller, MethodDisplay displayMethodAs) {
		log.debug("Getting JSONDoc for class: " + controller.getName());
		ApiDoc apiDoc = initApiDoc(controller);

		apiDoc.setSupportedversions(ApiVersionDoc.build(controller));
		apiDoc.setAuth(getApiAuthDocForController(controller));
		apiDoc.setMethods(getApiMethodDocs(controller, displayMethodAs));
		
		apiDoc = mergeApiDoc(controller, apiDoc);
		
		return apiDoc;
	}
	
	private Set<ApiMethodDoc> getApiMethodDocs(Class<?> controller, MethodDisplay displayMethodAs) {
		Set<ApiMethodDoc> apiMethodDocs = new TreeSet<ApiMethodDoc>();
		Set<Method> methods = jsondocMethods(controller);
		for (Method method : methods) {
			ApiMethodDoc apiMethodDoc = getApiMethodDoc(method, controller, displayMethodAs);
			apiMethodDocs.add(apiMethodDoc);
		}
		jsondocMethods.addAll(methods);
		allApiMethodDocs.addAll(apiMethodDocs);
		return apiMethodDocs;
	}
	
	private ApiMethodDoc getApiMethodDoc(Method method, Class<?> controller, MethodDisplay displayMethodAs) {
		ApiMethodDoc apiMethodDoc = initApiMethodDoc(method, controller);
		
		apiMethodDoc.setDisplayMethodAs(displayMethodAs);
		apiMethodDoc.setApierrors(ApiErrorDoc.build(method));
		apiMethodDoc.setSupportedversions(ApiVersionDoc.build(method));
		apiMethodDoc.setAuth(getApiAuthDocForMethod(method, method.getDeclaringClass()));

		apiMethodDoc = mergeApiMethodDoc(method, controller, apiMethodDoc);
		apiMethodDoc = validateApiMethodDoc(apiMethodDoc, displayMethodAs);
		
		return apiMethodDoc;
	}
	
	/**
	 * Gets the API flow documentation for the set of classes passed as argument
	 */
	public Set<ApiFlowDoc> getApiFlowDocs(Set<Class<?>> classes, List<ApiMethodDoc> apiMethodDocs) {
		Set<ApiFlowDoc> apiFlowDocs = new TreeSet<ApiFlowDoc>();
		for (Class<?> clazz : classes) {
			log.debug("Getting JSONDoc for class: " + clazz.getName());
			Method[] methods = clazz.getMethods();
			for (Method method : methods) {
				if(method.isAnnotationPresent(ApiFlow.class)) {
					ApiFlowDoc apiFlowDoc = getApiFlowDoc(method, apiMethodDocs);
					apiFlowDocs.add(apiFlowDoc);
				}	
			}
		}
		return apiFlowDocs;
	}
	
	private ApiFlowDoc getApiFlowDoc(Method method, List<ApiMethodDoc> apiMethodDocs) {
		ApiFlowDoc apiFlowDoc = ApiFlowDoc.buildFromAnnotation(method.getAnnotation(ApiFlow.class), apiMethodDocs);
		return apiFlowDoc;
	}

	/**
	 * This checks that some of the properties are correctly set to produce a meaningful documentation and a working playground. In case this does not happen
	 * an error string is added to the jsondocerrors list in ApiMethodDoc.
	 * It also checks that some properties are be set to produce a meaningful documentation. In case this does not happen
	 * an error string is added to the jsondocwarnings list in ApiMethodDoc.
	 * @param apiMethodDoc
	 * @return
	 */
	private ApiMethodDoc validateApiMethodDoc(ApiMethodDoc apiMethodDoc, MethodDisplay displayMethodAs) {
		final String ERROR_MISSING_METHOD_PATH 				= "Missing documentation data: path";
		final String ERROR_MISSING_PATH_PARAM_NAME 			= "Missing documentation data: path parameter name";
		final String ERROR_MISSING_QUERY_PARAM_NAME 		= "Missing documentation data: query parameter name";
		final String ERROR_MISSING_HEADER_NAME		 		= "Missing documentation data: header name";
		final String WARN_MISSING_METHOD_PRODUCES 			= "Missing documentation data: produces";
		final String WARN_MISSING_METHOD_CONSUMES 			= "Missing documentation data: consumes";
		final String HINT_MISSING_PATH_PARAM_DESCRIPTION 	= "Add description to ApiPathParam";
		final String HINT_MISSING_QUERY_PARAM_DESCRIPTION 	= "Add description to ApiQueryParam";
		final String HINT_MISSING_METHOD_DESCRIPTION 		= "Add description to ApiMethod";
		final String HINT_MISSING_METHOD_RESPONSE_OBJECT 	= "Add annotation ApiResponseObject to document the returned object";
		final String HINT_MISSING_METHOD_SUMMARY 			= "Method display set to SUMMARY, but summary info has not been specified";
		
		if(apiMethodDoc.getPath().trim().isEmpty()) {
			apiMethodDoc.setPath(ERROR_MISSING_METHOD_PATH);
			apiMethodDoc.addJsondocerror(ERROR_MISSING_METHOD_PATH);
		}
		
		if(apiMethodDoc.getSummary().trim().isEmpty() && displayMethodAs.equals(MethodDisplay.SUMMARY)) {
			// Fallback to path if summary is missing
			apiMethodDoc.setSummary(apiMethodDoc.getPath());
			apiMethodDoc.addJsondochint(HINT_MISSING_METHOD_SUMMARY);
		}
		
		for (ApiParamDoc apiParamDoc : apiMethodDoc.getPathparameters()) {
			if(apiParamDoc.getName().trim().isEmpty()) {
				apiMethodDoc.addJsondocerror(ERROR_MISSING_PATH_PARAM_NAME);
			}
			
			if(apiParamDoc.getDescription().trim().isEmpty()) {
				apiMethodDoc.addJsondochint(HINT_MISSING_PATH_PARAM_DESCRIPTION);
			}
		}
		
		for (ApiParamDoc apiParamDoc : apiMethodDoc.getQueryparameters()) {
			if(apiParamDoc.getName().trim().isEmpty()) {
				apiMethodDoc.addJsondocerror(ERROR_MISSING_QUERY_PARAM_NAME);
			}
			
			if(apiParamDoc.getDescription().trim().isEmpty()) {
				apiMethodDoc.addJsondochint(HINT_MISSING_QUERY_PARAM_DESCRIPTION);
			}
		}
		
		for (ApiHeaderDoc apiHeaderDoc : apiMethodDoc.getHeaders()) {
			if(apiHeaderDoc.getName().trim().isEmpty()) {
				apiMethodDoc.addJsondocerror(ERROR_MISSING_HEADER_NAME);
			}
		}
		
		if(apiMethodDoc.getProduces().isEmpty()) {
			apiMethodDoc.addJsondocwarning(WARN_MISSING_METHOD_PRODUCES);
		}
		
		if((apiMethodDoc.getVerb().contains(ApiVerb.POST) || apiMethodDoc.getVerb().contains(ApiVerb.PUT)) && apiMethodDoc.getConsumes().isEmpty()) {
			apiMethodDoc.addJsondocwarning(WARN_MISSING_METHOD_CONSUMES);
		}
		
		if(apiMethodDoc.getDescription().trim().isEmpty()) {
			apiMethodDoc.addJsondochint(HINT_MISSING_METHOD_DESCRIPTION);
		}
		
		if(apiMethodDoc.getResponse() == null) {
			apiMethodDoc.addJsondochint(HINT_MISSING_METHOD_RESPONSE_OBJECT);
		}
		
		return apiMethodDoc;
	}

	public Set<ApiObjectDoc> getApiObjectDocs(Set<Class<?>> classes) {
		Set<ApiObjectDoc> apiObjectDocs = new TreeSet<ApiObjectDoc>();
		for (Class<?> clazz : classes) {
			log.debug("Getting JSONDoc for class: " + clazz.getName());
			ApiObjectDoc apiObjectDoc = initApiObjectDoc(clazz);
			
			apiObjectDoc.setSupportedversions(ApiVersionDoc.build(clazz));
			
			apiObjectDoc = mergeApiObjectDoc(clazz, apiObjectDoc);
			
			if(apiObjectDoc != null) {
				apiObjectDoc = validateApiObjectDoc(apiObjectDoc);
				apiObjectDocs.add(apiObjectDoc);
			}
		}
		return apiObjectDocs;
	}
	
	private ApiObjectDoc validateApiObjectDoc(ApiObjectDoc apiObjectDoc) {
		final String HINT_MISSING_API_OBJECT_FIELD_DESCRIPTION 	= "Add description to field: %s";
		
		for (ApiObjectFieldDoc apiObjectFieldDoc : apiObjectDoc.getFields()) {
			if(apiObjectFieldDoc.getDescription().trim().isEmpty()) {
				apiObjectDoc.addJsondochint(String.format(HINT_MISSING_API_OBJECT_FIELD_DESCRIPTION, apiObjectFieldDoc.getName()));
			}
		}
		return apiObjectDoc;
	}
	
	public Map<String, Set<ApiDoc>> getApiDocsMap(Set<Class<?>> classes, MethodDisplay displayMethodAs) {
		Map<String, Set<ApiDoc>> apiDocsMap = new TreeMap<String, Set<ApiDoc>>();
		Set<ApiDoc> apiDocSet = getApiDocs(classes, displayMethodAs);
		for (ApiDoc apiDoc : apiDocSet) {
			if(apiDocsMap.containsKey(apiDoc.getGroup())) {
				apiDocsMap.get(apiDoc.getGroup()).add(apiDoc);
			} else {
				Set<ApiDoc> groupedPojoDocs = new TreeSet<ApiDoc>();
				groupedPojoDocs.add(apiDoc);
				apiDocsMap.put(apiDoc.getGroup(), groupedPojoDocs);
			}
		}
		return apiDocsMap;
	}
	
	public Map<String, Set<ApiObjectDoc>> getApiObjectsMap(Set<Class<?>> classes) {
		Map<String, Set<ApiObjectDoc>> objectsMap = new TreeMap<String, Set<ApiObjectDoc>>();
		Set<ApiObjectDoc> apiObjectDocSet = getApiObjectDocs(classes); 
		for (ApiObjectDoc apiObjectDoc : apiObjectDocSet) {
			if(objectsMap.containsKey(apiObjectDoc.getGroup())) {
				objectsMap.get(apiObjectDoc.getGroup()).add(apiObjectDoc);
			} else {
				Set<ApiObjectDoc> groupedPojoDocs = new TreeSet<ApiObjectDoc>();
				groupedPojoDocs.add(apiObjectDoc);
				objectsMap.put(apiObjectDoc.getGroup(), groupedPojoDocs);
			}
		}
		return objectsMap;
	}
	
	public Map<String, Set<ApiFlowDoc>> getApiFlowDocsMap(Set<Class<?>> classes, List<ApiMethodDoc> apiMethodDocs) {
		Map<String, Set<ApiFlowDoc>> apiFlowDocsMap = new TreeMap<String, Set<ApiFlowDoc>>();
		Set<ApiFlowDoc> apiFlowDocSet = getApiFlowDocs(classes, apiMethodDocs);
		for (ApiFlowDoc apiFlowDoc : apiFlowDocSet) {
			if(apiFlowDocsMap.containsKey(apiFlowDoc.getGroup())) {
				apiFlowDocsMap.get(apiFlowDoc.getGroup()).add(apiFlowDoc);
			} else {
				Set<ApiFlowDoc> groupedFlowDocs = new TreeSet<ApiFlowDoc>();
				groupedFlowDocs.add(apiFlowDoc);
				apiFlowDocsMap.put(apiFlowDoc.getGroup(), groupedFlowDocs);
			}
		}
		return apiFlowDocsMap;
	}

	private ApiAuthDoc getApiAuthDocForController(Class<?> clazz) {
		if(clazz.isAnnotationPresent(ApiAuthNone.class)) {
			return ApiAuthDoc.buildFromApiAuthNoneAnnotation(clazz.getAnnotation(ApiAuthNone.class));
		}
		
		if(clazz.isAnnotationPresent(ApiAuthBasic.class)) {
			return ApiAuthDoc.buildFromApiAuthBasicAnnotation(clazz.getAnnotation(ApiAuthBasic.class));
		}
		
		return null;
	}
	
	private ApiAuthDoc getApiAuthDocForMethod(Method method, Class<?> clazz) {
		if(method.isAnnotationPresent(ApiAuthNone.class)) {
			return ApiAuthDoc.buildFromApiAuthNoneAnnotation(method.getAnnotation(ApiAuthNone.class));
		}
		
		if(method.isAnnotationPresent(ApiAuthBasic.class)) {
			return ApiAuthDoc.buildFromApiAuthBasicAnnotation(method.getAnnotation(ApiAuthBasic.class));
		}
		
		return getApiAuthDocForController(clazz);
	}
	
	public static String[] enumConstantsToStringArray(Object[] enumConstants) {
		String[] sarr = new String[enumConstants.length];
		for (int i = 0; i < enumConstants.length; i++) {
			sarr[i] = String.valueOf(enumConstants[i]);
		}
		return sarr;
	}
	
}
