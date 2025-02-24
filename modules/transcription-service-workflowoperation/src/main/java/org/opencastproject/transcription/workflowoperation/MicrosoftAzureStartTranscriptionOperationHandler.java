/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.transcription.workflowoperation;

import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.Track;
import org.opencastproject.mediapackage.selector.AbstractMediaPackageElementSelector;
import org.opencastproject.mediapackage.selector.TrackSelector;
import org.opencastproject.serviceregistry.api.ServiceRegistry;
import org.opencastproject.transcription.api.TranscriptionService;
import org.opencastproject.transcription.api.TranscriptionServiceException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.ConfiguredTagsAndFlavors;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

@Component(
        immediate = true,
        service = WorkflowOperationHandler.class,
        property = {
                "service.description=Microsoft Azure Start Transcription Workflow Operation Handler",
                "workflow.operation=microsoft-azure-start-transcription"
        }
)
public class MicrosoftAzureStartTranscriptionOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(MicrosoftAzureStartTranscriptionOperationHandler.class);

  /** Workflow configuration option keys */
  static final String SOURCE_FLAVOR = "source-flavor";
  static final String SOURCE_TAG = "source-tag";
  static final String LANGUAGE_CODE = "language-code";
  static final String SKIP_IF_FLAVOR_EXISTS = "skip-if-flavor-exists";

  /** The transcription service */
  private TranscriptionService service = null;

  @Override
  @Activate
  protected void activate(ComponentContext cc) {
    super.activate(cc);
  }

  @Override
  public WorkflowOperationResult start(final WorkflowInstance workflowInstance, JobContext context)
          throws WorkflowOperationException {
    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();

    String skipOption = StringUtils.trimToNull(operation.getConfiguration(SKIP_IF_FLAVOR_EXISTS));
    if (skipOption != null) {
      MediaPackageElement[] mpes = mediaPackage.getElementsByFlavor(MediaPackageElementFlavor.parseFlavor(skipOption));
      if (mpes != null && mpes.length > 0) {
        logger.info(
                "Start transcription operation will be skipped because flavor {} already exists in the media package",
                skipOption);
        return createResult(Action.SKIP);
      }
    }

    logger.debug("Start transcription for mediapackage {} started", mediaPackage);

    // Check which tags have been configured
    ConfiguredTagsAndFlavors tagsAndFlavors = getTagsAndFlavors(
            workflowInstance, Configuration.many, Configuration.many, Configuration.none, Configuration.none);
    List<String> sourceTagOption = tagsAndFlavors.getSrcTags();
    List<MediaPackageElementFlavor> sourceFlavorOption = tagsAndFlavors.getSrcFlavors();

    AbstractMediaPackageElementSelector<Track> elementSelector = new TrackSelector();

    // Make sure either one of tags or flavors are provided
    if (sourceTagOption.isEmpty() && sourceFlavorOption.isEmpty()) {
      throw new WorkflowOperationException("No source tag or flavor have been specified!");
    }

    if (!sourceFlavorOption.isEmpty()) {
      MediaPackageElementFlavor flavor = sourceFlavorOption.get(0);
      elementSelector.addFlavor(flavor);
    }
    if (!sourceTagOption.isEmpty()) {
      elementSelector.addTag(sourceTagOption.get(0));
    }

    // Get language code if configured
    String langCode = operation.getConfiguration(LANGUAGE_CODE);
    if (StringUtils.isNotBlank(langCode)) {
      langCode = StringUtils.trim(langCode);
    }

    Collection<Track> elements = elementSelector.select(mediaPackage, false);
    Job job = null;
    for (Track track : elements) {
      try {
        job = service.startTranscription(mediaPackage.getIdentifier().toString(), track, langCode);
        // Only one job per media package
        break;
      } catch (TranscriptionServiceException e) {
        throw new WorkflowOperationException(e);
      }
    }

    if (job == null) {
      logger.info("No matching tracks found");
      return createResult(mediaPackage, Action.CONTINUE);
    }

    // Wait for the jobs to return
    if (!waitForStatus(job).isSuccess()) {
      throw new WorkflowOperationException("Transcription job did not complete successfully");
    }
    // Return OK means that the microsoft azure job was created, but not finished yet

    logger.debug("External transcription job for mediapackage {} was created", mediaPackage);

    // Results are empty, we should get a callback when transcription is done
    return createResult(Action.CONTINUE);
  }

  @Reference(target = "(provider=microsoft.azure)")
  public void setTranscriptionService(TranscriptionService service) {
    this.service = service;
  }

  @Reference
  @Override
  public void setServiceRegistry(ServiceRegistry serviceRegistry) {
    super.setServiceRegistry(serviceRegistry);
  }

}
