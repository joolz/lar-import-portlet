package joolz.test;

import com.liferay.faces.portal.context.LiferayFacesContext;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.lar.ExportImportHelper;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.model.Group;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portal.service.LayoutServiceUtil;
import com.liferay.portlet.documentlibrary.service.DLFileEntryLocalServiceUtil;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;

import nl.ou.dlwo.products.model.Product;
import nl.ou.dlwo.products.service.ProductLocalServiceUtil;

import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.UploadedFile;

@ManagedBean
@ViewScoped
public class MyBean implements Serializable {
	private static final Log LOG = LogFactoryUtil.getLog(MyBean.class);
	private static final long serialVersionUID = 42L;
	private UploadedFile uploadedFile;
	private transient Integer maxFileSize = null;
	private transient Boolean uploadEnabled = null;
	private transient List<Group> allowedSites = null;

	public UploadedFile getUploadedFile() {
		return uploadedFile;
	}

	public void setUploadedFile(UploadedFile uploadedFile) {
		this.uploadedFile = uploadedFile;
	}

	public Integer getMaxFileSize() {
		if (maxFileSize == null) {
			String prop = PropsUtil.get("dl.file.max.size");
			maxFileSize = Integer.valueOf(prop);
		}
		return maxFileSize;
	}

	protected void deleteTempFileEntry(long groupId, String folderName) throws PortalException, SystemException {
		String[] tempFileEntryNames = LayoutServiceUtil.getTempFileEntryNames(groupId, folderName);
		for (String tempFileEntryName : tempFileEntryNames) {
			LayoutServiceUtil.deleteTempFileEntry(groupId, tempFileEntryName, folderName);
		}
	}

	public void uploadListener(FileUploadEvent event) {
		uploadedFile = event.getFile();
		LOG.debug("file is " + uploadedFile.getFileName());

		LiferayFacesContext lfc = LiferayFacesContext.getInstance();

		try {

			deleteTempFileEntry(lfc.getScopeGroupId(), ExportImportHelper.TEMP_FOLDER_NAME);

			FileEntry fileEntry = LayoutServiceUtil.addTempFileEntry(lfc.getScopeGroupId(), uploadedFile.getFileName(),
					ExportImportHelper.TEMP_FOLDER_NAME, uploadedFile.getInputstream(), uploadedFile.getContentType());

			InputStream inputStream = DLFileEntryLocalServiceUtil.getFileAsStream(fileEntry.getFileEntryId(),
					fileEntry.getVersion(), false);

			Map<String, String[]> parameterMap = null;

			LayoutServiceUtil.importLayoutsInBackground(fileEntry.getTitle(), lfc.getScopeGroupId(), false,
					parameterMap, inputStream);

			deleteTempFileEntry(lfc.getScopeGroupId(), ExportImportHelper.TEMP_FOLDER_NAME);

			lfc.addGlobalSuccessInfoMessage();

		} catch (Exception e) {
			LOG.error(e);
			lfc.addGlobalUnexpectedErrorMessage();
		}
	}

	public boolean isUploadEnabled() {
		if (uploadEnabled == null) {
			getAllowedSites(); // TODO temporary here
			LOG.debug("Determine uploadEnabled");
			uploadEnabled = false;
			try {
				LiferayFacesContext lfc = LiferayFacesContext.getInstance();
				Product product = ProductLocalServiceUtil.getProductByG(lfc.getScopeGroupId());
				if (product != null) {
					LOG.debug("Found product " + product.getName());
					int state = product.getState();
					LOG.debug("has state " + state);
					if (WorkflowConstants.STATUS_DRAFT == state) {
						LOG.debug("product is draft, set uploadEnabled to true");
						uploadEnabled = true;
					}
				}
			} catch (SystemException e) {
				LOG.error(e);
			}
		}
		return uploadEnabled;
	}

	private boolean isSiteDraft(final long groupId) {
		boolean result = false;
		try {
			LiferayFacesContext lfc = LiferayFacesContext.getInstance();
			LOG.debug("Total number of products " + ProductLocalServiceUtil.getProductsCount());
			Product product = ProductLocalServiceUtil.getProductByG(groupId);
			if (product != null) {
				LOG.debug("Found product " + product.getName() + " for site "
						+ GroupLocalServiceUtil.getGroup(groupId).getName());
				int state = product.getState();
				LOG.debug("has state " + state);
				if (WorkflowConstants.STATUS_DRAFT == state) {
					LOG.debug("product is draft, set uploadEnabled to true");
					result = true;
				}
			}
		} catch (SystemException | PortalException e) {
			LOG.error(e);
		}
		return result;
	}

	public List<Group> getAllowedSites() {
		if (allowedSites == null) {
			allowedSites = new ArrayList<Group>();
			try {
				for (Group group : GroupLocalServiceUtil.getGroups(-1, -1)) {
					if (group.isSite() && isSiteDraft(group.getGroupId())) {
						LOG.debug("found group " + group.getName());
					}
				}
			} catch (SystemException e) {
				LOG.error(e);
			}

		}
		return allowedSites;
	}
}