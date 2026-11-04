import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CORE_REVIEW_DOCUMENT_IDS,
  type DocumentViewerMode,
  type EvidenceAnchor,
  type ReviewDocumentKind,
  type ReviewDocumentUiState,
} from "./workspaceTypes";

const SUPPORTED_FILE_PATTERN = /\.(pdf|png|jpe?g)$/i;

function emptyDocument(
  clientId: string,
  kind: ReviewDocumentKind,
  label: string,
  required: boolean,
): ReviewDocumentUiState {
  return {
    clientId,
    kind,
    label,
    required,
    status: "NOT_ATTACHED",
    fileName: null,
    fileSize: null,
    mimeType: null,
    lastModified: null,
  };
}

function initialDocuments(): ReviewDocumentUiState[] {
  return [
    emptyDocument(CORE_REVIEW_DOCUMENT_IDS.contract, "CONTRACT_BODY", "合同正文", true),
    emptyDocument(CORE_REVIEW_DOCUMENT_IDS.bidding, "BIDDING_DOCUMENT", "招标文件", false),
    emptyDocument(CORE_REVIEW_DOCUMENT_IDS.tender, "TENDER_DOCUMENT", "投标文件", false),
    emptyDocument(CORE_REVIEW_DOCUMENT_IDS.award, "AWARD_NOTICE", "中标通知书", true),
    emptyDocument(CORE_REVIEW_DOCUMENT_IDS.other, "OTHER", "其他材料", false),
  ];
}

function fileLabel(kind: ReviewDocumentKind, fileName: string): string {
  const baseName = fileName.replace(/\.[^.]+$/, "");
  if (kind === "TENDER_DOCUMENT") return baseName ? `投标文件 · ${baseName}` : "投标文件";
  if (kind === "OTHER") return baseName ? `其他材料 · ${baseName}` : "其他材料";
  if (kind === "CONTRACT_BODY") return "合同正文";
  if (kind === "BIDDING_DOCUMENT") return "招标文件";
  return "中标通知书";
}

function isMultiFileKind(kind: ReviewDocumentKind): boolean {
  return kind === "TENDER_DOCUMENT" || kind === "OTHER";
}

export interface ReviewDocumentsState {
  documents: ReviewDocumentUiState[];
  activeDocument: ReviewDocumentUiState;
  activeDocumentId: string;
  viewerMode: DocumentViewerMode;
  activeEvidence: EvidenceAnchor | null;
  selectedMaterialCount: number;
  activeObjectUrl: string | null;
  setActiveDocumentId: (clientId: string) => void;
  setViewerMode: (mode: DocumentViewerMode) => void;
  addLocalFile: (kind: ReviewDocumentKind, file: File) => void;
  removeLocalFile: (clientId: string) => void;
  focusEvidence: (anchor: EvidenceAnchor) => void;
  clearEvidence: () => void;
}

export function useReviewDocuments(contractId: string): ReviewDocumentsState {
  const [documents, setDocuments] = useState<ReviewDocumentUiState[]>(initialDocuments);
  const [activeDocumentId, setActiveDocumentIdState] = useState(CORE_REVIEW_DOCUMENT_IDS.contract);
  const [viewerMode, setViewerModeState] = useState<DocumentViewerMode>("ORIGINAL");
  const [activeEvidence, setActiveEvidence] = useState<EvidenceAnchor | null>(null);
  const filesRef = useRef(new Map<string, File>());
  const objectUrlsRef = useRef(new Map<string, string>());
  const nextClientIdRef = useRef(1);

  const revokeLocalFile = useCallback((clientId: string) => {
    const objectUrl = objectUrlsRef.current.get(clientId);
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrlsRef.current.delete(clientId);
    filesRef.current.delete(clientId);
  }, []);

  const revokeAllLocalFiles = useCallback(() => {
    objectUrlsRef.current.forEach((objectUrl) => URL.revokeObjectURL(objectUrl));
    objectUrlsRef.current.clear();
    filesRef.current.clear();
  }, []);

  useEffect(() => {
    revokeAllLocalFiles();
    nextClientIdRef.current = 1;
    setDocuments(initialDocuments());
    setActiveDocumentIdState(CORE_REVIEW_DOCUMENT_IDS.contract);
    setViewerModeState("ORIGINAL");
    setActiveEvidence(null);
    return revokeAllLocalFiles;
  }, [contractId, revokeAllLocalFiles]);

  const setActiveDocumentId = useCallback((clientId: string) => {
    setActiveDocumentIdState(clientId);
    setActiveEvidence((current) => current?.documentClientId === clientId ? current : null);
  }, []);

  const setViewerMode = useCallback((mode: DocumentViewerMode) => {
    setViewerModeState(mode);
    if (mode === "ORIGINAL") setActiveEvidence(null);
  }, []);

  const addLocalFile = useCallback((kind: ReviewDocumentKind, file: File) => {
    if (!SUPPORTED_FILE_PATTERN.test(file.name)) {
      throw new Error("当前仅支持 PDF、PNG、JPG、JPEG 文件");
    }

    const reusable = documents.find((document) =>
      document.kind === kind && (!isMultiFileKind(kind) || document.status === "NOT_ATTACHED"),
    );
    const targetId = reusable?.clientId
      ?? `${kind.toLowerCase()}-${nextClientIdRef.current++}`;

    revokeLocalFile(targetId);
    filesRef.current.set(targetId, file);
    objectUrlsRef.current.set(targetId, URL.createObjectURL(file));

    const nextDocument: ReviewDocumentUiState = {
      clientId: targetId,
      kind,
      label: fileLabel(kind, file.name),
      required: kind === "CONTRACT_BODY" || kind === "AWARD_NOTICE",
      status: "LOCAL_SELECTED",
      fileName: file.name,
      fileSize: file.size,
      mimeType: file.type || null,
      lastModified: file.lastModified,
    };

    setDocuments(reusable
      ? documents.map((document) => document.clientId === targetId ? nextDocument : document)
      : [...documents, nextDocument]);
    setActiveDocumentIdState(targetId);
    setViewerModeState("ORIGINAL");
    setActiveEvidence(null);
  }, [documents, revokeLocalFile]);

  const removeLocalFile = useCallback((clientId: string) => {
    const target = documents.find((document) => document.clientId === clientId);
    if (!target) return;
    revokeLocalFile(clientId);
    const keepPlaceholder = Object.values(CORE_REVIEW_DOCUMENT_IDS).includes(
      clientId as (typeof CORE_REVIEW_DOCUMENT_IDS)[keyof typeof CORE_REVIEW_DOCUMENT_IDS],
    );
    const next = keepPlaceholder
      ? documents.map((document) => document.clientId === clientId
        ? emptyDocument(document.clientId, document.kind, fileLabel(document.kind, ""), document.required)
        : document)
      : documents.filter((document) => document.clientId !== clientId);
    setDocuments(next);
    setActiveDocumentIdState((activeId) => activeId === clientId
      ? (next[0]?.clientId ?? CORE_REVIEW_DOCUMENT_IDS.contract)
      : activeId);
    setActiveEvidence(null);
  }, [documents, revokeLocalFile]);

  const focusEvidence = useCallback((anchor: EvidenceAnchor) => {
    setActiveDocumentIdState(anchor.documentClientId);
    setViewerModeState("TEXT");
    setActiveEvidence(anchor);
  }, []);

  const activeDocument = useMemo(
    () => documents.find((document) => document.clientId === activeDocumentId) ?? documents[0],
    [activeDocumentId, documents],
  );

  return {
    documents,
    activeDocument,
    activeDocumentId,
    viewerMode,
    activeEvidence,
    selectedMaterialCount: documents.filter((document) => document.status === "LOCAL_SELECTED").length,
    activeObjectUrl: objectUrlsRef.current.get(activeDocument.clientId) ?? null,
    setActiveDocumentId,
    setViewerMode,
    addLocalFile,
    removeLocalFile,
    focusEvidence,
    clearEvidence: () => setActiveEvidence(null),
  };
}
