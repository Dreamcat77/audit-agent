package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One fail-closed selection boundary shared by all selected-material review branches. */
@Service
public class SelectedProjectMaterialReadService {
    private final ProjectApplicationService projects;
    private final ProjectParsedTextReadService parsedTexts;

    public SelectedProjectMaterialReadService(ProjectApplicationService projects,
                                              ProjectParsedTextReadService parsedTexts) {
        this.projects = projects;
        this.parsedTexts = parsedTexts;
    }

    public List<SelectedProjectMaterial> read(UUID projectId, List<UUID> selectedFileIds) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        validateSelection(selectedFileIds);
        Map<UUID, ProjectFile> indexed = indexProjectFiles(projectId);
        List<SelectedProjectMaterial> selected = new ArrayList<>(selectedFileIds.size());
        for (UUID fileId : selectedFileIds) {
            ProjectFile file = indexed.get(fileId);
            if (file == null) throw validationFailure("selected project file does not exist in this project");
            if (!eligible(file)) {
                throw validationFailure("selected project file is not ready for analysis");
            }
            String text = parsedTexts.read(file);
            if (text == null || text.isBlank()) {
                throw validationFailure("selected project file has no persisted parsed text");
            }
            selected.add(new SelectedProjectMaterial(file, text));
        }
        return List.copyOf(selected);
    }

    private Map<UUID, ProjectFile> indexProjectFiles(UUID projectId) {
        Map<UUID, ProjectFile> indexed = new HashMap<>();
        for (ProjectFile file : projects.files(projectId)) {
            if (!projectId.equals(file.projectId())) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "project file ownership is inconsistent");
            }
            if (indexed.put(file.id(), file) != null) {
                throw new BusinessException(BusinessErrorCode.CONFLICT,
                        "project file identity is inconsistent");
            }
        }
        return indexed;
    }

    private static void validateSelection(List<UUID> selectedFileIds) {
        if (selectedFileIds == null || selectedFileIds.isEmpty() || selectedFileIds.size() > 50) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "fileIds must contain between 1 and 50 entries");
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID fileId : selectedFileIds) {
            if (fileId == null || !unique.add(fileId)) {
                throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                        "fileIds must contain unique non-null entries");
            }
        }
    }

    private static boolean eligible(ProjectFile file) {
        return file.category() != ProjectFileCategory.PACKAGE
                && file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                && file.parseStatus() == ProjectFileParseStatus.PARSED;
    }

    private static BusinessException validationFailure(String message) {
        return new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, message);
    }

    public record SelectedProjectMaterial(ProjectFile file, String text) {
        public SelectedProjectMaterial {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }
}
