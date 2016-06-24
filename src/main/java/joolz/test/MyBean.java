/*
 * Copyright (C) 2016 Open Universiteit Nederland
 *
 * This library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library.  If not, see &lt;http://www.gnu.org/licenses/&gt;.
 */
package joolz.test;

import com.liferay.faces.portal.context.LiferayFacesContext;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.lar.ExportImportHelper;
import com.liferay.portal.kernel.lar.PortletDataHandlerKeys;
import com.liferay.portal.kernel.lar.UserIdStrategy;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.Group;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portal.service.LayoutServiceUtil;
import com.liferay.portlet.documentlibrary.service.DLFileEntryLocalServiceUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.faces.model.SelectItem;

import nl.ou.dlwo.products.model.Product;
import nl.ou.dlwo.products.service.ProductLocalServiceUtil;

import org.primefaces.event.FileUploadEvent;

/**
 * The Class MyBean.
 */
@ManagedBean
@ViewScoped
public class MyBean implements Serializable {
	private static final Log LOG = LogFactoryUtil.getLog(MyBean.class);
	private static final long serialVersionUID = 42L;
	private File uploadedFile = null;
	private transient Integer maxFileSize = null;
	private transient List<SelectItem> sites = null;
	private String site;
	private static final String TMPFILE_PREFIX = "dlwo-larimport";
	private static final String TMPFILE_POSTFIX = ".lar";

	public Integer getMaxFileSize() {
		if (maxFileSize == null) {
			String prop = PropsUtil.get("dl.file.max.size");
			maxFileSize = Integer.valueOf(prop);
		}
		return maxFileSize;
	}

	/**
	 * Delete temp file entry.
	 *
	 * @param groupId
	 *            the group id
	 * @param folderName
	 *            the folder name
	 * @throws PortalException
	 *             the portal exception
	 * @throws SystemException
	 *             the system exception
	 */
	protected void deleteTempFileEntry(long groupId, String folderName) throws PortalException, SystemException {
		String[] tempFileEntryNames = LayoutServiceUtil.getTempFileEntryNames(groupId, folderName);
		for (String tempFileEntryName : tempFileEntryNames) {
			LayoutServiceUtil.deleteTempFileEntry(groupId, tempFileEntryName, folderName);
		}
	}

	/**
	 * Upload listener for a single upload that will be imported right away.
	 *
	 * @param event
	 *            the event
	 */
	public void uploadListener(FileUploadEvent event) {
		LOG.debug("file is " + event.getFile().getFileName());

		InputStream inputStream = null;
		OutputStream outputStream = null;
		try {
			inputStream = event.getFile().getInputstream();
			uploadedFile = File.createTempFile(TMPFILE_PREFIX, TMPFILE_POSTFIX);
			outputStream = new FileOutputStream(uploadedFile);

			int read = 0;
			byte[] bytes = new byte[1024];

			while ((read = inputStream.read(bytes)) != -1) {
				outputStream.write(bytes, 0, read);
			}
		} catch (IOException e) {
			LOG.error(e);
		} finally {
			try {
				inputStream.close();
			} catch (IOException e) {
				LOG.error(e);
			}
		}
		if (outputStream != null) {
			try {
				outputStream.flush();
				outputStream.close();
			} catch (IOException e) {
				LOG.error(e);
			}
		}

	}

	/**
	 * Checks if at least one site is OK for upload.
	 *
	 * @return true, if is upload enabled
	 */
	public boolean isUploadEnabled() {
		return getSites().size() > 0;
	}

	/**
	 * Checks if a site is draft.
	 *
	 * @param groupId
	 *            the group id
	 * @return true, if is site draft
	 */
	private boolean isSiteDraft(final long groupId) {
		boolean result = false;
		try {
			Product product = ProductLocalServiceUtil.getProductByG(groupId);
			if (product != null) {
				LOG.debug("Found product " + product.getName());
				int state = product.getState();
				if (WorkflowConstants.STATUS_DRAFT == state) {
					LOG.debug("Site " + product.getGroupId() + " has product " + product.getProductId()
							+ " that is draft");
					result = true;
				}
			}
		} catch (SystemException e) {
			LOG.error(e);
		}
		return result;
	}

	/**
	 * Gets a list of sites that are OK for import.
	 *
	 * @return the allowed sites
	 */
	public List<SelectItem> getSites() {
		if (sites == null) {
			sites = new ArrayList<SelectItem>();
			try {
				for (Group group : GroupLocalServiceUtil.getGroups(-1, -1)) {
					if (group.isSite() && isSiteDraft(group.getGroupId())) {
						sites.add(new SelectItem(group.getGroupId(), group.getName()));
					}
				}
			} catch (SystemException e) {
				LOG.error(e);
			}

		}
		return sites;
	}

	public void setSite(String site) {
		this.site = site;
	}

	public String getSite() {
		return site;
	}

	@PreDestroy
	public void cleanup() {
		if (uploadedFile.exists()) {
			uploadedFile.delete();
		}
	}

	/**
	 * Do actual import.
	 *
	 * @return the string
	 */
	public String doImport() {
		LiferayFacesContext lfc = LiferayFacesContext.getInstance();
		if (getSite() == null || getSite().trim().isEmpty()) {
			LOG.debug("No site selected");
			lfc.addGlobalErrorMessage("No site selected");
		} else if (uploadedFile == null) {
			LOG.debug("No file uploaded");
			lfc.addGlobalErrorMessage("No file uploaded");
		} else {

			long groupId = Long.valueOf(getSite());
			try {

				deleteTempFileEntry(groupId, ExportImportHelper.TEMP_FOLDER_NAME);

				FileEntry fileEntry = LayoutServiceUtil.addTempFileEntry(groupId, uploadedFile.getName(),
						ExportImportHelper.TEMP_FOLDER_NAME, new FileInputStream(uploadedFile),
						URLConnection.guessContentTypeFromName(uploadedFile.getName()));

				InputStream inputStream = DLFileEntryLocalServiceUtil.getFileAsStream(fileEntry.getFileEntryId(),
						fileEntry.getVersion(), false);

				// Import parameters.
				Map<String, String[]> parameterMap = new HashMap<String, String[]>();

				parameterMap.put(PortletDataHandlerKeys.CATEGORIES, new String[] { Boolean.TRUE.toString() });

				// pages
				parameterMap.put(PortletDataHandlerKeys.DELETE_MISSING_LAYOUTS,
						new String[] { Boolean.FALSE.toString() });
				parameterMap.put(PortletDataHandlerKeys.LAYOUT_SET_SETTINGS, new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.THEME_REFERENCE, new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.LOGO, new String[] { Boolean.TRUE.toString() });

				// all applications
				parameterMap.put(PortletDataHandlerKeys.PORTLET_CONFIGURATION_ALL,
						new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.PORTLET_SETUP_ALL, new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.PORTLET_ARCHIVED_SETUPS_ALL,
						new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.PORTLET_USER_PREFERENCES_ALL,
						new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.PORTLET_CONFIGURATION_ALL,
						new String[] { Boolean.TRUE.toString() });

				// all content. TODO (?) exclude calendar
				parameterMap.put(PortletDataHandlerKeys.PORTLET_DATA_ALL, new String[] { Boolean.TRUE.toString() });
				parameterMap.put(PortletDataHandlerKeys.DELETE_PORTLET_DATA, new String[] { Boolean.FALSE.toString() });

				// permissions
				parameterMap.put(PortletDataHandlerKeys.PERMISSIONS, new String[] { Boolean.TRUE.toString() });

				// copy as new
				parameterMap.put(PortletDataHandlerKeys.DATA_STRATEGY,
						new String[] { PortletDataHandlerKeys.DATA_STRATEGY_COPY_AS_NEW });

				// use the original author
				parameterMap.put(PortletDataHandlerKeys.USER_ID_STRATEGY,
						new String[] { UserIdStrategy.CURRENT_USER_ID });

				LayoutServiceUtil.importLayoutsInBackground(fileEntry.getTitle(), groupId, false, parameterMap,
						inputStream);

				lfc.addGlobalSuccessInfoMessage();

			} catch (Exception e) {
				LOG.error(e);
				lfc.addGlobalUnexpectedErrorMessage();
			} finally {
				try {
					deleteTempFileEntry(groupId, ExportImportHelper.TEMP_FOLDER_NAME);
					uploadedFile.delete();
				} catch (PortalException | SystemException e) {
					LOG.error(e.getMessage());
				}
			}
		}
		return StringPool.BLANK;
	}
}