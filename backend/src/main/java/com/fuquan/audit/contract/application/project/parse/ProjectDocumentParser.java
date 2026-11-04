package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.io.InputStream;

public interface ProjectDocumentParser {
    boolean supports(ProjectFile file);
    ParsedProjectDocument parse(ProjectFile file, InputStream input);
}
