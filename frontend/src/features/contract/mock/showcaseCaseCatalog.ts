import type { Contract, Finding, ReviewResult } from "../types";
import {
  CORE_REVIEW_DOCUMENT_IDS,
  type EvidenceAnchor,
  type ReviewDocumentKind,
  type StructuredFactKey,
} from "../workspaceTypes";

export const F02_MOCK_CASE_IDS = {
  pass: "00000000-0000-0000-0000-000000000101",
  risk: "00000000-0000-0000-0000-000000000102",
  officeTerminals: "00000000-0000-0000-0000-000000000201",
  dataCenterMaintenance: "00000000-0000-0000-0000-000000000202",
  networkSwitches: "00000000-0000-0000-0000-000000000203",
  conferenceSystem: "00000000-0000-0000-0000-000000000204",
} as const;

export const F02_MOCK_CONTRACT_IDS = {
  pass: "CT-2024-0089",
  risk: "CT-2024-0094",
  officeTerminals: "CT-2026-0101",
  dataCenterMaintenance: "CT-2026-0102",
  networkSwitches: "CT-2026-0103",
  conferenceSystem: "CT-2026-0104",
} as const;

export const showcaseCaseOrder = [
  F02_MOCK_CONTRACT_IDS.risk,
  F02_MOCK_CONTRACT_IDS.pass,
  F02_MOCK_CONTRACT_IDS.officeTerminals,
  F02_MOCK_CONTRACT_IDS.dataCenterMaintenance,
  F02_MOCK_CONTRACT_IDS.networkSwitches,
  F02_MOCK_CONTRACT_IDS.conferenceSystem,
] as const;

export interface ContractMockCase {
  contract: Contract;
  reviewResult: ReviewResult;
}

export interface ContractDocumentMock {
  textByKind: Partial<Record<ReviewDocumentKind, string>>;
  factEvidence: Partial<Record<StructuredFactKey, EvidenceAnchor>>;
  findingEvidence: Readonly<Record<string, EvidenceAnchor>>;
}

export interface ShowcasePresentationTime {
  createdAt: string;
  updatedAt: string;
}

export interface ShowcaseProjectPresentation extends ShowcasePresentationTime {
  projectName: string;
  projectNo: string;
}

interface CanonicalLineItem {
  itemKey: string;
  itemName: string;
  quantity: number;
  unitPrice: number;
  declaredAmount: number;
}

interface CanonicalAward {
  noticeNo: string;
  projectName: string;
  winningBidder: string;
  winningBidderCreditCode: string;
  winningAmount: number;
  winningDate: string;
}

interface CanonicalCase {
  sequence: number;
  projectNo: string;
  contractId: string;
  contractNo: string;
  contractName: string;
  contractType: string;
  partyA: string;
  partyB: string;
  partyBCreditCode: string;
  contractAmount: number;
  amountInWords: string;
  amountInWordsValue: number;
  reviewCompletedAt: string;
  lineItems: readonly CanonicalLineItem[];
  award: CanonicalAward;
  presentation: ShowcasePresentationTime;
}

const canonicalCases: readonly CanonicalCase[] = [
  {
    sequence: 102,
    projectNo: "XM-2024-0094",
    contractId: F02_MOCK_CASE_IDS.risk,
    contractNo: F02_MOCK_CONTRACT_IDS.risk,
    contractName: "综合业务系统运维服务合同",
    contractType: "PROCUREMENT",
    partyA: "XX市公共服务中心",
    partyB: "北京科信技术有限公司",
    partyBCreditCode: "91110108MA0B009401",
    contractAmount: 876000,
    amountInWords: "人民币捌拾柒万陆仟元整",
    amountInWordsValue: 876000,
    reviewCompletedAt: "2026-07-14T14:35:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "综合业务系统年度运维服务", quantity: 1, unitPrice: 876000, declaredAmount: 876000 },
    ],
    award: {
      noticeNo: "ZB-2024-0094",
      projectName: "综合业务系统运维服务项目",
      winningBidder: "北京科信信息技术有限公司",
      winningBidderCreditCode: "91110108MA0B009499",
      winningAmount: 849000,
      winningDate: "2026-08-01",
    },
    presentation: {
      createdAt: "2026-07-14T14:30:00+08:00",
      updatedAt: "2026-07-14T14:32:00+08:00",
    },
  },
  {
    sequence: 101,
    projectNo: "XM-2024-0089",
    contractId: F02_MOCK_CASE_IDS.pass,
    contractNo: F02_MOCK_CONTRACT_IDS.pass,
    contractName: "车辆GPS定位系统合同",
    contractType: "PROCUREMENT",
    partyA: "XX市公共服务中心",
    partyB: "D信息技术公司",
    partyBCreditCode: "91310000MA0A008901",
    contractAmount: 678000,
    amountInWords: "人民币陆拾柒万捌仟元整",
    amountInWordsValue: 678000,
    reviewCompletedAt: "2026-07-11T10:05:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "车辆GPS定位系统服务", quantity: 1, unitPrice: 678000, declaredAmount: 678000 },
    ],
    award: {
      noticeNo: "ZB-2024-0089",
      projectName: "车辆GPS定位系统项目",
      winningBidder: "D信息技术公司",
      winningBidderCreditCode: "91310000MA0A008901",
      winningAmount: 678000,
      winningDate: "2026-07-01",
    },
    presentation: {
      createdAt: "2026-07-11T10:00:00+08:00",
      updatedAt: "2026-07-11T10:02:00+08:00",
    },
  },
  {
    sequence: 201,
    projectNo: "XM-2026-0101",
    contractId: F02_MOCK_CASE_IDS.officeTerminals,
    contractNo: F02_MOCK_CONTRACT_IDS.officeTerminals,
    contractName: "智慧办公终端采购合同",
    contractType: "PROCUREMENT",
    partyA: "XX市公共服务中心",
    partyB: "上海云衡数字科技有限公司",
    partyBCreditCode: "91310115MA0A010101",
    contractAmount: 528000,
    amountInWords: "人民币伍拾贰万捌仟元整",
    amountInWordsValue: 528000,
    reviewCompletedAt: "2026-08-18T09:25:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "办公终端设备", quantity: 120, unitPrice: 3200, declaredAmount: 384000 },
      { itemKey: "ITEM-2", itemName: "显示器及扩展坞", quantity: 12, unitPrice: 8000, declaredAmount: 96000 },
      { itemKey: "ITEM-3", itemName: "部署与数据迁移服务", quantity: 1, unitPrice: 48000, declaredAmount: 48000 },
    ],
    award: {
      noticeNo: "ZB-2026-0101",
      projectName: "智慧办公终端采购项目",
      winningBidder: "上海云衡数字科技有限公司",
      winningBidderCreditCode: "91310115MA0A010101",
      winningAmount: 528000,
      winningDate: "2026-08-15",
    },
    presentation: {
      createdAt: "2026-08-18T09:20:00+08:00",
      updatedAt: "2026-08-18T09:22:00+08:00",
    },
  },
  {
    sequence: 202,
    projectNo: "XM-2026-0102",
    contractId: F02_MOCK_CASE_IDS.dataCenterMaintenance,
    contractNo: F02_MOCK_CONTRACT_IDS.dataCenterMaintenance,
    contractName: "数据中心设备维保服务合同",
    contractType: "SERVICE",
    partyA: "XX市公共服务中心",
    partyB: "江苏启衡信息技术有限公司",
    partyBCreditCode: "91320100MA0B010202",
    contractAmount: 640000,
    amountInWords: "人民币陆拾叁万伍仟元整",
    amountInWordsValue: 635000,
    reviewCompletedAt: "2026-08-18T10:26:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "服务器及存储设备年度维保", quantity: 1, unitPrice: 520000, declaredAmount: 520000 },
      { itemKey: "ITEM-2", itemName: "驻场巡检与应急支持", quantity: 1, unitPrice: 120000, declaredAmount: 120000 },
    ],
    award: {
      noticeNo: "ZB-2026-0102",
      projectName: "数据中心设备维保服务项目",
      winningBidder: "江苏启衡信息技术有限公司",
      winningBidderCreditCode: "91320100MA0B010202",
      winningAmount: 640000,
      winningDate: "2026-08-16",
    },
    presentation: {
      createdAt: "2026-08-18T10:20:00+08:00",
      updatedAt: "2026-08-18T10:23:00+08:00",
    },
  },
  {
    sequence: 203,
    projectNo: "XM-2026-0103",
    contractId: F02_MOCK_CASE_IDS.networkSwitches,
    contractNo: F02_MOCK_CONTRACT_IDS.networkSwitches,
    contractName: "园区网络交换设备采购合同",
    contractType: "PROCUREMENT",
    partyA: "XX市行政服务中心",
    partyB: "杭州联拓网络科技有限公司",
    partyBCreditCode: "91330106MA0C010303",
    contractAmount: 456000,
    amountInWords: "人民币肆拾伍万陆仟元整",
    amountInWordsValue: 456000,
    reviewCompletedAt: "2026-08-19T14:16:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "接入交换设备", quantity: 100, unitPrice: 3000, declaredAmount: 305000 },
      { itemKey: "ITEM-2", itemName: "核心交换与管理平台", quantity: 1, unitPrice: 151000, declaredAmount: 151000 },
    ],
    award: {
      noticeNo: "ZB-2026-0103",
      projectName: "园区网络交换设备采购项目",
      winningBidder: "杭州联拓网络科技有限公司",
      winningBidderCreditCode: "91330106MA0C010303",
      winningAmount: 456000,
      winningDate: "2026-08-17",
    },
    presentation: {
      createdAt: "2026-08-19T14:10:00+08:00",
      updatedAt: "2026-08-19T14:13:00+08:00",
    },
  },
  {
    sequence: 204,
    projectNo: "XM-2026-0104",
    contractId: F02_MOCK_CASE_IDS.conferenceSystem,
    contractNo: F02_MOCK_CONTRACT_IDS.conferenceSystem,
    contractName: "会议室音视频系统采购合同",
    contractType: "PROCUREMENT",
    partyA: "XX市政务服务中心",
    partyB: "苏州景明数字技术有限公司",
    partyBCreditCode: "91320594MA0D010404",
    contractAmount: 760000,
    amountInWords: "人民币柒拾陆万元整",
    amountInWordsValue: 760000,
    reviewCompletedAt: "2026-08-20T09:27:00+08:00",
    lineItems: [
      { itemKey: "ITEM-1", itemName: "会议终端及显示设备", quantity: 100, unitPrice: 6000, declaredAmount: 600000 },
      { itemKey: "ITEM-2", itemName: "会议控制与音频设备", quantity: 15, unitPrice: 10000, declaredAmount: 150000 },
    ],
    award: {
      noticeNo: "ZB-2026-0104",
      projectName: "会议室音视频系统采购项目",
      winningBidder: "苏州景明数字技术有限公司",
      winningBidderCreditCode: "91320594MA0D010404",
      winningAmount: 760000,
      winningDate: "2026-08-18",
    },
    presentation: {
      createdAt: "2026-08-20T09:20:00+08:00",
      updatedAt: "2026-08-20T09:24:00+08:00",
    },
  },
];

function uuid(group: number, sequence: number, index = 0): string {
  const suffix = String(group * 1_000_000 + sequence * 100 + index).padStart(12, "0");
  return `00000000-0000-0000-0000-${suffix}`;
}

function amount(value: number): string {
  return value.toFixed(2);
}

function finding(
  definition: CanonicalCase,
  index: number,
  ruleCode: string,
  itemKey: string | null,
  passed: boolean,
  actualValue: string,
  expectedValue: string,
  passDetail: string,
  failDetail: string,
): Finding {
  return {
    id: uuid(4, definition.sequence, index),
    ruleCode,
    itemKey,
    checkStatus: passed ? "PASS" : "FAIL",
    riskLevel: passed ? "GREEN" : "RED",
    blockSign: !passed,
    actualValue,
    expectedValue,
    detail: passed ? passDetail : failDetail,
    ruleVersion: 1,
    createdAt: definition.reviewCompletedAt,
  };
}

function buildReviewResult(definition: CanonicalCase): ReviewResult {
  const findings: Finding[] = [];
  let index = 1;
  const wordsMatch = definition.contractAmount === definition.amountInWordsValue;
  findings.push(finding(
    definition,
    index++,
    "F02_AMOUNT_WORDS_MATCH",
    null,
    wordsMatch,
    amount(definition.contractAmount),
    amount(definition.amountInWordsValue),
    "合同金额大写与数字金额一致。",
    `合同数字金额为 ${amount(definition.contractAmount)} 元，金额大写表达为 ${amount(definition.amountInWordsValue)} 元，两者不一致。`,
  ));

  definition.lineItems.forEach((line) => {
    const calculated = line.quantity * line.unitPrice;
    const passed = calculated === line.declaredAmount;
    findings.push(finding(
      definition,
      index++,
      "F02_LINE_ITEM_ARITHMETIC",
      line.itemKey,
      passed,
      amount(line.declaredAmount),
      amount(calculated),
      `${line.itemKey} 数量乘以单价与申报金额一致。`,
      `${line.itemKey} 申报金额为 ${amount(line.declaredAmount)} 元，数量乘以单价为 ${amount(calculated)} 元，两者不一致。`,
    ));
  });

  const declaredSum = definition.lineItems.reduce((total, line) => total + line.declaredAmount, 0);
  findings.push(finding(
    definition,
    index++,
    "F02_LINE_SUM_MATCH",
    null,
    declaredSum === definition.contractAmount,
    amount(declaredSum),
    amount(definition.contractAmount),
    "合同明细申报金额合计与合同总金额一致。",
    `合同明细申报金额合计为 ${amount(declaredSum)} 元，合同总金额为 ${amount(definition.contractAmount)} 元，两者不一致。`,
  ));

  findings.push(finding(
    definition,
    index++,
    "F02_AWARD_AMOUNT_MATCH",
    null,
    definition.contractAmount === definition.award.winningAmount,
    amount(definition.contractAmount),
    amount(definition.award.winningAmount),
    "合同金额与中标金额一致。",
    `合同金额为 ${amount(definition.contractAmount)} 元，中标金额为 ${amount(definition.award.winningAmount)} 元，两者不一致。`,
  ));

  const supplierMatch = definition.partyB === definition.award.winningBidder
    && definition.partyBCreditCode === definition.award.winningBidderCreditCode;
  findings.push(finding(
    definition,
    index,
    "F02_AWARD_SUPPLIER_MATCH",
    null,
    supplierMatch,
    `${definition.partyB} / ${definition.partyBCreditCode}`,
    `${definition.award.winningBidder} / ${definition.award.winningBidderCreditCode}`,
    "合同乙方与中标供应商一致。",
    "合同乙方与中标供应商名称或统一社会信用代码不一致。",
  ));

  const hasFailure = findings.some((item) => item.checkStatus === "FAIL");
  return {
    taskId: uuid(3, definition.sequence),
    status: "DONE",
    reviewType: "F02_DETERMINISTIC",
    overallRisk: hasFailure ? "RED" : "GREEN",
    blockSign: hasFailure,
    replayed: false,
    findings,
  };
}

function buildContract(definition: CanonicalCase): Contract {
  const sourceDate = `${definition.award.winningDate}T00:00:00Z`;
  return {
    id: definition.contractId,
    contractNo: definition.contractNo,
    contractName: definition.contractName,
    contractType: definition.contractType,
    partyA: definition.partyA,
    partyB: definition.partyB,
    partyBCreditCode: definition.partyBCreditCode,
    contractAmount: definition.contractAmount,
    amountInWords: definition.amountInWords,
    status: "READY_FOR_REVIEW",
    versionNo: 1,
    createdAt: sourceDate,
    updatedAt: sourceDate,
    lineItems: definition.lineItems.map((line, index) => ({
      id: uuid(1, definition.sequence, index + 1),
      itemKey: line.itemKey,
      itemName: line.itemName,
      quantity: line.quantity,
      unitPrice: line.unitPrice,
      declaredAmount: line.declaredAmount,
      sortNo: index + 1,
    })),
    awardNotice: {
      id: uuid(2, definition.sequence),
      noticeNo: definition.award.noticeNo,
      projectName: definition.award.projectName,
      winningBidder: definition.award.winningBidder,
      winningBidderCreditCode: definition.award.winningBidderCreditCode,
      winningAmount: definition.award.winningAmount,
      winningDate: definition.award.winningDate,
      sourceRef: `customer-case-${definition.contractNo.slice(-4)}`,
    },
  };
}

function anchor(documentClientId: string, text: string, excerpt: string): EvidenceAnchor {
  const startOffset = text.indexOf(excerpt);
  if (startOffset < 0) return { documentClientId, excerpt };
  return { documentClientId, excerpt, startOffset, endOffset: startOffset + excerpt.length };
}

function buildDocument(definition: CanonicalCase): ContractDocumentMock {
  const lineTexts = definition.lineItems.map((line) =>
    `${line.itemKey}：${line.itemName}；数量 ${line.quantity}；单价人民币 ${amount(line.unitPrice)} 元；申报金额人民币 ${amount(line.declaredAmount)} 元。`,
  );
  const declaredSum = definition.lineItems.reduce((total, line) => total + line.declaredAmount, 0);
  const contractText = `${definition.contractName}

一、合同基本信息
合同编号：${definition.contractNo}
合同名称：${definition.contractName}
甲方：${definition.partyA}
乙方：${definition.partyB}
乙方统一社会信用代码：${definition.partyBCreditCode}
合同总金额：人民币 ${amount(definition.contractAmount)} 元
金额大写：${definition.amountInWords}

二、合同标的和分项
${lineTexts.join("\n")}
合同分项申报金额合计：人民币 ${amount(declaredSum)} 元。

三、交付与实施
乙方应按甲方书面计划完成供货、部署或服务实施，实施过程不得影响甲方正常业务运行，并提交完整实施记录。

四、验收
甲方依据合同、采购文件和乙方响应文件组织验收；验收不合格的，乙方应在甲方指定期限内完成整改并重新提交验收。

五、付款
合同价款按验收与履约进度支付，乙方应先提交合法有效的发票及付款申请材料，具体支付以财政资金支付程序为准。

六、质量保证
乙方保证交付的产品或服务符合国家标准、采购文件及合同约定，并在质量保证期内承担修复、更换及技术支持责任。

七、双方职责
甲方负责提供必要的实施条件并按约组织验收；乙方负责人员、设备、进度、质量和安全管理，未经同意不得转包。

八、数据保密
双方对履约中知悉的业务数据、技术资料及未公开信息承担保密义务，未经对方书面许可不得向第三方披露。

九、知识产权
乙方保证其交付成果不侵犯第三方合法权利；因侵权产生的责任和损失由责任方依法承担。

十、违约责任
任何一方未按合同约定履行义务，应承担继续履行、采取补救措施或赔偿损失等违约责任。

十一、变更与解除
合同变更应采用书面形式并经双方授权代表确认；发生法定或约定解除情形时，守约方可依法解除合同。

十二、不可抗力
因不可抗力不能履行合同的，受影响方应及时通知并在合理期限内提供证明，双方协商处理后续履约安排。

十三、争议解决
双方因本合同产生争议应先友好协商；协商不成的，依法向甲方所在地有管辖权的人民法院提起诉讼。

十四、生效条款
本合同自双方授权代表签字并加盖公章之日起生效，合同附件与本合同具有同等法律效力。

十五、签署信息
甲方（盖章）：${definition.partyA}
乙方（盖章）：${definition.partyB}
授权代表：以签署页为准
签署日期：以正式签署页为准`;

  const biddingText = `${definition.award.projectName}采购文件

项目编号：${definition.projectNo}
采购单位：${definition.partyA}
项目名称：${definition.award.projectName}
采购预算：人民币 ${amount(definition.contractAmount)} 元
采购内容：${definition.lineItems.map((line) => line.itemName).join("、")}。
投标人应对供货、实施、验收、售后服务、保密和知识产权要求作出完整响应。`;

  const tenderText = `${definition.award.projectName}响应文件

项目编号：${definition.projectNo}
响应供应商：${definition.award.winningBidder}
统一社会信用代码：${definition.award.winningBidderCreditCode}
响应报价：人民币 ${amount(definition.award.winningAmount)} 元
响应内容：供应商承诺按照采购文件要求完成交付、实施、验收和质量保证服务。`;

  const awardText = `中标通知书

通知书编号：${definition.award.noticeNo}
项目名称：${definition.award.projectName}
中标供应商：${definition.award.winningBidder}
统一社会信用代码：${definition.award.winningBidderCreditCode}
中标金额：人民币 ${amount(definition.award.winningAmount)} 元
中标日期：${definition.award.winningDate}

请中标供应商按照采购文件和响应文件约定，与采购单位办理合同签署及后续履约手续。`;

  const contractAmount = anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `合同总金额：人民币 ${amount(definition.contractAmount)} 元`);
  const amountInWords = anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `金额大写：${definition.amountInWords}`);
  const lineAnchors = Object.fromEntries(definition.lineItems.map((line, index) => [
    `F02_LINE_ITEM_ARITHMETIC:${line.itemKey}`,
    anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, lineTexts[index]),
  ]));
  const firstFailedLine = definition.lineItems.find((line) => line.quantity * line.unitPrice !== line.declaredAmount)
    ?? definition.lineItems[0];
  const lineItems = anchor(
    CORE_REVIEW_DOCUMENT_IDS.contract,
    contractText,
    lineTexts[definition.lineItems.indexOf(firstFailedLine)],
  );
  const lineSum = anchor(
    CORE_REVIEW_DOCUMENT_IDS.contract,
    contractText,
    `合同分项申报金额合计：人民币 ${amount(declaredSum)} 元。`,
  );
  const awardAmount = anchor(CORE_REVIEW_DOCUMENT_IDS.award, awardText, `中标金额：人民币 ${amount(definition.award.winningAmount)} 元`);
  const awardSupplier = anchor(CORE_REVIEW_DOCUMENT_IDS.award, awardText, `中标供应商：${definition.award.winningBidder}`);

  return {
    textByKind: {
      CONTRACT_BODY: contractText,
      BIDDING_DOCUMENT: biddingText,
      TENDER_DOCUMENT: tenderText,
      AWARD_NOTICE: awardText,
    },
    factEvidence: {
      contractNo: anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `合同编号：${definition.contractNo}`),
      contractName: anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `合同名称：${definition.contractName}`),
      partyA: anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `甲方：${definition.partyA}`),
      partyB: anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `乙方：${definition.partyB}`),
      partyBCreditCode: anchor(CORE_REVIEW_DOCUMENT_IDS.contract, contractText, `乙方统一社会信用代码：${definition.partyBCreditCode}`),
      contractAmount,
      amountInWords,
      lineItems,
      awardSupplier,
      awardAmount,
    },
    findingEvidence: {
      F02_AMOUNT_WORDS_MATCH: amountInWords,
      F02_LINE_ITEM_ARITHMETIC: lineItems,
      ...lineAnchors,
      F02_LINE_SUM_MATCH: lineSum,
      F02_AWARD_AMOUNT_MATCH: awardAmount,
      F02_AWARD_SUPPLIER_MATCH: awardSupplier,
    },
  };
}

export const contractMockCases: Readonly<Record<string, ContractMockCase>> = Object.fromEntries(
  canonicalCases.map((definition) => [definition.contractNo, {
    contract: buildContract(definition),
    reviewResult: buildReviewResult(definition),
  }]),
);

const contractDocumentMocks: Readonly<Record<string, ContractDocumentMock>> = Object.fromEntries(
  canonicalCases.map((definition) => [definition.contractNo, buildDocument(definition)]),
);

export const showcasePresentationTimes: Readonly<Record<string, ShowcasePresentationTime>> = Object.fromEntries(
  canonicalCases.map((definition) => [definition.contractNo, definition.presentation]),
);

/** Showcase-only presentation metadata; it is not a persisted Project entity. */
export const showcaseProjectPresentations: Readonly<Record<string, ShowcaseProjectPresentation>> =
  Object.fromEntries(canonicalCases.map((definition) => [definition.contractNo, {
    projectName: definition.award.projectName,
    projectNo: definition.projectNo,
    ...definition.presentation,
  }]));

export function getContractDocumentMock(contractId: string): ContractDocumentMock | null {
  return contractDocumentMocks[contractId] ?? null;
}
