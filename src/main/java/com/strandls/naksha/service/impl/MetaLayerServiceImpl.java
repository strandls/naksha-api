package com.strandls.naksha.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;
import javax.naming.directory.InvalidAttributesException;
import javax.servlet.http.HttpServletRequest;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.json.simple.parser.ParseException;
import org.pac4j.core.profile.CommonProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strandls.authentication_utility.util.AuthUtil;
import com.strandls.naksha.ApiConstants;
import com.strandls.naksha.NakshaConfig;
import com.strandls.naksha.dao.MetaLayerDao;
import com.strandls.naksha.pojo.MetaLayer;
import com.strandls.naksha.pojo.OGR2OGR;
import com.strandls.naksha.pojo.enumtype.DownloadAccess;
import com.strandls.naksha.pojo.enumtype.LayerStatus;
import com.strandls.naksha.pojo.request.LayerDownload;
import com.strandls.naksha.pojo.request.LayerFileDescription;
import com.strandls.naksha.pojo.request.MetaData;
import com.strandls.naksha.pojo.request.MetaLayerEdit;
import com.strandls.naksha.pojo.response.LocationInfo;
import com.strandls.naksha.pojo.response.ObservationLocationInfo;
import com.strandls.naksha.pojo.response.TOCLayer;
import com.strandls.naksha.service.AbstractService;
import com.strandls.naksha.service.GeoserverService;
import com.strandls.naksha.service.GeoserverStyleService;
import com.strandls.naksha.service.MailService;
import com.strandls.naksha.service.MetaLayerService;
import com.strandls.naksha.utils.MetaLayerUtil;
import com.strandls.naksha.utils.Utils;
import com.strandls.user.ApiException;
import com.strandls.user.controller.UserServiceApi;
import com.strandls.user.pojo.UserIbp;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.io.WKTReader;

import it.geosolutions.geoserver.rest.decoder.RESTLayer;
import net.minidev.json.JSONArray;

public class MetaLayerServiceImpl extends AbstractService<MetaLayer> implements MetaLayerService {

	private final Logger logger = LoggerFactory.getLogger(MetaLayerServiceImpl.class);

	@Inject
	private ObjectMapper objectMapper;

	@Inject
	private GeoserverService geoserverService;

	@Inject
	private GeoserverStyleService geoserverStyleService;

	@Inject
	private UserServiceApi userServiceApi;

	@Inject
	private MetaLayerDao metaLayerDao;

	@Inject
	private GeometryFactory geoFactory;

	@Inject
	private MailService mailService;

	public static final String DOWNLOAD_BASE_LOCATION = NakshaConfig.getString(MetaLayerUtil.TEMP_DIR_PATH)
			+ File.separator + "temp_zip";

	@Inject
	public MetaLayerServiceImpl(MetaLayerDao dao) {
		super(dao);
	}

	@Override
	public MetaLayer findByLayerTableName(String layerName) {
		return findByPropertyWithCondtion("layerTableName", layerName, "=");
	}

	@Override
	public List<TOCLayer> getTOCList(HttpServletRequest request, Integer limit, Integer offset, boolean showOnlyPending)
			throws ApiException, com.vividsolutions.jts.io.ParseException, URISyntaxException {

		CommonProfile userProfile = AuthUtil.getProfileFromRequest(request);

		List<MetaLayer> metaLayers = findAll(request, limit, offset);
		List<TOCLayer> layerLists = new ArrayList<TOCLayer>();
		boolean isAdmin = Utils.isAdmin(request);

		for (MetaLayer metaLayer : metaLayers) {

			if ((!isAdmin && LayerStatus.PENDING.equals(metaLayer.getLayerStatus()))
					|| (showOnlyPending && !LayerStatus.PENDING.equals(metaLayer.getLayerStatus())))
				continue;

			Long authorId = metaLayer.getUploaderUserId();

			UserIbp userIbp = userServiceApi.getUserIbp(authorId + "");

			Boolean isDownloadable = checkDownLoadAccess(userProfile, metaLayer);

			List<List<Double>> bbox = getBoundingBox(metaLayer);
			String thumbnail = getThumbnail(metaLayer, bbox);
			TOCLayer tocLayer = new TOCLayer(metaLayer, userIbp, isDownloadable, bbox, thumbnail);
			layerLists.add(tocLayer);
		}
		return layerLists;
	}

	private String getThumbnail(MetaLayer metaLayer, List<List<Double>> bbox) throws URISyntaxException {
		String bboxValue = bbox.get(0).get(0) + "," + bbox.get(0).get(1) + "," + bbox.get(1).get(0) + ","
				+ bbox.get(1).get(1);

		String uri = ApiConstants.GEOSERVER + ApiConstants.THUMBNAILS + "/" + MetaLayerService.WORKSPACE + "/"
				+ metaLayer.getLayerTableName();

		URIBuilder builder = new URIBuilder(uri);

		ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add(new BasicNameValuePair("layers", metaLayer.getLayerTableName()));
		params.add(new BasicNameValuePair("bbox", bboxValue));
		params.add(new BasicNameValuePair("request", "GetMap"));
		params.add(new BasicNameValuePair("service", "WMS"));
		params.add(new BasicNameValuePair("version", "1.1.0"));
		params.add(new BasicNameValuePair("format", "image/gif"));

		builder.setParameters(params);
		return builder.build().toString();
	}

	private List<List<Double>> getBoundingBox(MetaLayer metaLayer) throws com.vividsolutions.jts.io.ParseException {
		String bbox = metaLayerDao.getBoundingBox(metaLayer.getLayerTableName());

		WKTReader reader = new WKTReader(geoFactory);
		Geometry topology = reader.read(bbox);

		Geometry envelop = topology.getEnvelope();

		Double top = envelop.getCoordinates()[0].x;
		Double left = envelop.getCoordinates()[0].y;
		Double bottom = envelop.getCoordinates()[2].x;
		Double right = envelop.getCoordinates()[2].y;

		List<List<Double>> boundingBox = new ArrayList<List<Double>>();

		List<Double> topLeft = new ArrayList<Double>();
		List<Double> bottomRight = new ArrayList<Double>();

		topLeft.add(top);
		topLeft.add(left);

		bottomRight.add(bottom);
		bottomRight.add(right);

		boundingBox.add(topLeft);
		boundingBox.add(bottomRight);

		return boundingBox;
	}

	@Override
	public List<MetaLayer> findAll(HttpServletRequest request, Integer limit, Integer offset) {
		return metaLayerDao.findAll(limit, offset);
	}

	public void uploadGeoTiff(String geoLayerName, String inputGeoTiffFileLocation, Map<String, Object> result)
			throws IOException {
		File inputGeoTiffFile = new File(inputGeoTiffFileLocation);
		boolean isPublished = geoserverService.publishGeoTiffLayer(WORKSPACE, geoLayerName, inputGeoTiffFile);

		if (!isPublished) {
			throw new IOException("Geoserver publication of layer failed");
		}
		result.put("Uplaoded on geoserver", geoLayerName);
		RESTLayer layer = geoserverService.getManager().getReader().getLayer(WORKSPACE, geoLayerName);
		result.put("Geoserver layer url", layer.getResourceUrl());
	}

	@Override
	public Map<String, Object> uploadLayer(HttpServletRequest request, FormDataMultiPart multiPart)
			throws IOException, ParseException, InvalidAttributesException, InterruptedException {
		Map<String, Object> result = new HashMap<String, Object>();

		String jsonString = MetaLayerUtil.getMetadataAsJson(multiPart).toJSONString();
		MetaData metaData = objectMapper.readValue(jsonString, MetaData.class);
		CommonProfile profile = AuthUtil.getProfileFromRequest(request);
		long uploaderUserId = Long.parseLong(profile.getId());
		Map<String, String> layerColumnDescription = metaData.getLayerColumnDescription();
		LayerFileDescription layerFileDescription = metaData.getLayerFileDescription();
		String fileType = layerFileDescription.getFileType();

		Map<String, String> copiedFiles;
		String ogrInputFileLocation;
		String layerName;

		if ("shp".equals(fileType)) {
			copiedFiles = MetaLayerUtil.copyFiles(multiPart);
			ogrInputFileLocation = copiedFiles.get("shp");
			layerName = multiPart.getField("shp").getContentDisposition().getFileName().split("\\.")[0].toLowerCase();
		} else if ("csv".equals(fileType)) {
			copiedFiles = MetaLayerUtil.copyCSVFile(multiPart, layerFileDescription);
			ogrInputFileLocation = copiedFiles.get("vrt");
			layerName = multiPart.getField("csv").getContentDisposition().getFileName().split("\\.")[0].toLowerCase();
		} else if ("tif".equals(fileType)) {
			copiedFiles = MetaLayerUtil.copyGeneralFile(multiPart, "tif", false);
			ogrInputFileLocation = copiedFiles.get("tif");
			layerName = multiPart.getField("tif").getContentDisposition().getFileName().split("\\.")[0].toLowerCase();
		} else {
			throw new IllegalArgumentException("Invalid file type");
		}

		String dirPath = copiedFiles.get("dirPath");
		result.put("Files copied to", dirPath);

		MetaLayer metaLayer = new MetaLayer(metaData, uploaderUserId, dirPath);
		metaLayer = save(metaLayer);
		result.put("Meta layer table entry", metaLayer.getId());

		String layerTableName = "lyr_" + metaLayer.getId() + "_" + layerName.trim().replaceAll("\\s+", "_");
		metaLayer.setLayerTableName(layerTableName);
		update(metaLayer);

		if ("tif".equals(fileType)) {
			uploadGeoTiff(layerTableName, ogrInputFileLocation, result);
			return result;
		}
		try {
			createDBTable(layerTableName, ogrInputFileLocation, layerColumnDescription, layerFileDescription, result);
		} catch (Exception e) {
			// Roll back
			MetaLayerUtil.deleteFiles(dirPath);
			metaLayerDao.delete(metaLayer);
			throw new IOException("Table creation failed");
		}

		List<String> keywords = new ArrayList<String>();
		keywords.add(layerTableName);

		List<String> styles = geoserverStyleService.publishAllStyles(layerTableName, WORKSPACE);
		boolean isPublished = geoserverService.publishLayer(WORKSPACE, DATASTORE, layerTableName, null, layerTableName,
				keywords, styles);
		if (!isPublished) {
			// roll back
			MetaLayerUtil.deleteFiles(dirPath);
			metaLayerDao.delete(metaLayer);
			metaLayerDao.dropTable(layerTableName);
			throw new IOException("Geoserver publication of layer failed");
		}

		metaLayer.setLayerStatus(LayerStatus.PENDING);
		update(metaLayer);

		result.put("Uplaoded on geoserver", layerTableName);
		RESTLayer layer = geoserverService.getManager().getReader().getLayer(WORKSPACE, layerTableName);
		result.put("Geoserver layer url", layer.getResourceUrl());
		return result;
	}

	private void createDBTable(String layerTableName, String ogrInputFileLocation,
			Map<String, String> layerColumnDescription, LayerFileDescription layerFileDescription,
			Map<String, Object> result) throws InvalidAttributesException, InterruptedException, IOException {

		String encoding = layerFileDescription.getEncoding();

		OGR2OGR ogr2ogr = new OGR2OGR(OGR2OGR.SHP_TO_POSTGRES, null, layerTableName, "precision=NO", null,
				ogrInputFileLocation, encoding);

		Process process = ogr2ogr.execute();
		if (process == null) {
			throw new IOException("Layer upload on the postgis failed");
		} else {
			process.waitFor();
			result.put("Table created for layer", layerTableName);
		}
		process = ogr2ogr.addColumnDescription(layerTableName, layerColumnDescription);
		if (process == null) {
			throw new IOException("Comment could not be added to table");
		} else {
			process.waitFor();
			result.put("Comments added", "success");
		}
	}

	@Override
	public Map<String, String> prepareDownloadLayer(HttpServletRequest request, LayerDownload layerDownload)
			throws InvalidAttributesException, InterruptedException, IOException {

		Map<String, String> retValue = new HashMap<String, String>();

		CommonProfile profile = AuthUtil.getProfileFromRequest(request);

		if (!checkDownLoadAccess(profile, layerDownload)) {
			retValue.put("failed", "User is not authorized to download the layer");
			return retValue;
		}

		String uri = request.getRequestURI();
		String hashKey = UUID.randomUUID().toString();

		ExecutorService service = Executors.newFixedThreadPool(10);
		service.execute(() -> {
			try {
				runDownloadLayer(profile.getId(), uri, hashKey, layerDownload);
			} catch (InvalidAttributesException | InterruptedException | IOException e) {
				logger.error(e.getMessage());
				Thread.currentThread().interrupt();
			}
		});

		String layerName = layerDownload.getLayerName();

		retValue.put("url", uri + "/" + hashKey + "/" + layerName);
		retValue.put("success", "The layer download process has started. You will receive the mail shortly");
		return retValue;
	}

	private boolean checkDownLoadAccess(CommonProfile profile, LayerDownload layerDownload) {
		MetaLayer metaLayer = findByLayerTableName(layerDownload.getLayerName());
		return checkDownLoadAccess(profile, metaLayer);
	}

	private boolean checkDownLoadAccess(CommonProfile profile, MetaLayer metaLayer) {
		if (profile == null)
			return false;

		JSONArray roles = (JSONArray) profile.getAttribute("roles");
		if (roles.contains("ROLE_ADMIN"))
			return true;

		if (metaLayer == null)
			return false;

		return metaLayer.getDownloadAccess().equals(DownloadAccess.ALL)
				|| metaLayer.getUploaderUserId().equals(Long.parseLong(profile.getId()));
	}

	public void runDownloadLayer(String authorId, String uri, String hashKey, LayerDownload layerDownload)
			throws InvalidAttributesException, InterruptedException, IOException {

		String layerName = layerDownload.getLayerName();

		List<String> attributeList = layerDownload.getAttributeList();
		List<String> filterArray = layerDownload.getFilterArray();

		File directory = new File(DOWNLOAD_BASE_LOCATION);
		if (!directory.exists()) {
			directory.mkdir();
		}

		String shapeFileDirectoryPath = DOWNLOAD_BASE_LOCATION + File.separator + hashKey;
		File shapeFileDirectory = new File(shapeFileDirectoryPath);
		if (!shapeFileDirectory.exists()) {
			shapeFileDirectory.mkdir();
		}

		shapeFileDirectoryPath += File.separator + layerName;
		shapeFileDirectory = new File(shapeFileDirectoryPath);
		if (!shapeFileDirectory.exists()) {
			shapeFileDirectory.mkdir();
		}

		StringBuilder attributeString = new StringBuilder();
		if (!attributeList.isEmpty()) {
			for (String attribute : attributeList) {
				attributeString.append(attribute + ", ");
			}
			attributeString.append("wkb_geometry ");
		} else
			attributeString.append("*");

		for (String filter : filterArray) {
			logger.debug(filter);
		}

		String query = "select " + attributeString + " from " + layerName;

		shapeFileDirectoryPath = shapeFileDirectory.getAbsolutePath();
		OGR2OGR ogr2ogr = new OGR2OGR(OGR2OGR.POSTGRES_TO_SHP, null, layerName, null, query, shapeFileDirectoryPath,
				null);
		Process process = ogr2ogr.execute();
		if (process == null) {
			throw new IOException("Shape file creation failed");
		} else {
			process.waitFor();
		}

		String zipFileLocation = shapeFileDirectoryPath + ".zip";

		zipFolder(zipFileLocation, shapeFileDirectory);

		logger.debug("{} / {} / {}", uri, hashKey, layerName);

		String url = uri + "/" + hashKey + "/" + layerName;

		mailService.sendMail(authorId, url, "naksha");
		// TODO : send mail notification for download url
		// return directory.getAbsolutePath();
	}

	public void zipFolder(String zipFileLocation, File fileDirectory) throws IOException {
		FileOutputStream fos = new FileOutputStream(zipFileLocation);
		try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
			for (File fileToZip : fileDirectory.listFiles()) {
				try (FileInputStream fis = new FileInputStream(fileToZip)) {
					ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
					zipOut.putNextEntry(zipEntry);

					byte[] bytes = new byte[1024];
					int length;
					while ((length = fis.read(bytes)) >= 0) {
						zipOut.write(bytes, 0, length);
					}
				}
			}
		}
	}

	@Override
	public String getFileLocation(String hashKey, String layerName) {
		return DOWNLOAD_BASE_LOCATION + File.separator + hashKey + File.separator + layerName + ".zip";
	}

	@Override
	public MetaLayer updateMataLayer(HttpServletRequest request, MetaLayerEdit metaLayerEdit) throws IOException {
		MetaLayer metaLayer = findById(metaLayerEdit.getId());
		if (Utils.isAdmin(request) || Utils.isOwner(metaLayer.getUploaderUserId(), request)) {
			metaLayer = metaLayerEdit.update(metaLayerEdit, metaLayer);
			return update(metaLayer);
		} else {
			throw new IOException("User is unauthorized to edit the layer");
		}
	}

	@Override
	public MetaLayer makeLayerActive(String layerName) {
		MetaLayer metaLayer = findByLayerTableName(layerName);
		metaLayer.setLayerStatus(LayerStatus.ACTIVE);
		return update(metaLayer);
	}

	@Override
	public MetaLayer makeLayerPending(String layerName) {
		MetaLayer metaLayer = findByLayerTableName(layerName);
		metaLayer.setLayerStatus(LayerStatus.PENDING);
		return update(metaLayer);
	}

	@Override
	public MetaLayer removeLayer(String layerName) {

		MetaLayer metaLayer = findByLayerTableName(layerName);
		metaLayer.setLayerStatus(LayerStatus.INACTIVE);
		update(metaLayer);
		return metaLayer;
	}

	@Override
	public MetaLayer deleteLayer(String layerName) {
		MetaLayer metaLayer = findByLayerTableName(layerName);
		return deleteLayer(metaLayer);
	}

	@Override
	public List<MetaLayer> cleanupInactiveLayers() {
		List<MetaLayer> layers = metaLayerDao.getAllInactiveLayer();

		List<MetaLayer> deletedLayers = new ArrayList<MetaLayer>();
		for (MetaLayer metaLayer : layers) {
			deletedLayers.add(deleteLayer(metaLayer));
		}

		return deletedLayers;
	}

	public MetaLayer deleteLayer(MetaLayer metaLayer) {
		String layerName = metaLayer.getLayerTableName();

		// Remove the copied files from the file system. (Need to take a call on this)
		String dirPath = metaLayer.getDirPath();
		MetaLayerUtil.deleteFiles(dirPath);

		// remove-published layer from the geoserver
		geoserverService.removeLayer(WORKSPACE, layerName);

		// remove the style from the geoserver
		geoserverStyleService.unpublishAllStyles(layerName, WORKSPACE);

		// Drop table from the database
		metaLayerDao.dropTable(layerName);

		// Delete the entry in the .
		return metaLayerDao.delete(metaLayer);
	}

	@Override
	public ObservationLocationInfo getLayerInfo(String lon, String lat) {
		try {
			String soil = getAttributeValueAtLatlon("descriptio", INDIA_SOIL, lon, lat);
			String temp = getAttributeValueAtLatlon("temp_c", INDIA_TEMPERATURE, lon, lat);
			String rainfall = getAttributeValueAtLatlon("rain_range", INDIA_RAINFALLZONE, lon, lat);
			String tahsil = getAttributeValueAtLatlon("tahsil", INDIA_TAHSIL, lon, lat);
			String forestType = getAttributeValueAtLatlon("type_desc", INDIA_FOREST_TYPE, lon, lat);

			if (soil == null && temp == null && rainfall == null && tahsil == null && forestType == null)
				return null;

			return new ObservationLocationInfo(soil, temp, rainfall, tahsil, forestType);
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		return null;

	}

	private String getAttributeValueAtLatlon(String attribute, String layerName, String lon, String lat) {

		try {
			String queryStr = "SELECT " + attribute + " from " + layerName + " where st_contains" + "(" + layerName
					+ "." + MetaLayerService.GEOMETRY_COLUMN_NAME + ", ST_GeomFromText('POINT(" + lon + " " + lat
					+ ")',0))";
			List<Object> result = metaLayerDao.executeQueryForSingleResult(queryStr);

			if (result.isEmpty())
				return null;

			return result.get(0).toString();
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		return null;

	}

	public LocationInfo getLocationInfo(String lat, String lon) {
		String queryStr = "SELECT state,district,tahsil from " + INDIA_TAHSIL + " where st_contains" + "("
				+ INDIA_TAHSIL + "." + MetaLayerService.GEOMETRY_COLUMN_NAME + ", ST_GeomFromText('POINT(" + lon + " "
				+ lat + ")',0))";

		List<Object[]> result = metaLayerDao.executeQueryForLocationInfo(queryStr);
		LocationInfo locationResponse = new LocationInfo();

		if (result.size() > 0) {
			Object[] values = result.get(0);
			locationResponse.setState(values[0].toString());
			locationResponse.setDistrict(values[1].toString());
			locationResponse.setTahsil(values[2].toString());
		}

		return locationResponse;
	}
}
