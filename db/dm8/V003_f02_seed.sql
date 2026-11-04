-- ============================================
-- F02 最小测试合同数据（0089 / 0094）
-- TEST/DEMO SEED ONLY；不是 migration，禁止在生产环境执行。
-- 仅向尚不存在这些固定 fixture ID 的测试 schema 执行一次。
-- 本脚本绝不 DELETE、UPDATE、MERGE 或覆盖现有合同及其审核证据。
-- 包含：
-- 1) CONTRACT
-- 2) CONTRACT_LINE_ITEM
-- 3) CONTRACT_AWARD_NOTICE
-- ============================================

-- ============================================
-- 1) CONTRACT
-- ============================================

INSERT INTO CONTRACT (
    ID,
    CONTRACT_NO,
    CONTRACT_NAME,
    CONTRACT_TYPE,
    PARTY_A,
    PARTY_B,
    PARTY_B_CREDIT_CODE,
    CONTRACT_AMOUNT,
    AMOUNT_IN_WORDS,
    STATUS,
    VERSION_NO,
    CREATED_AT,
    UPDATED_AT
) VALUES (
    '00000000-0000-0000-0000-000000000101',
    'CT-2024-0089',
    '车辆GPS定位系统合同',
    'PROCUREMENT',
    'XX市公共服务中心',
    'D信息技术公司',
    '91310000MOCK008901',
    678000.00,
    '人民币陆拾柒万捌仟元整',
    'READY_FOR_REVIEW',
    1,
    TO_TIMESTAMP('2026-07-11 00:00:00', 'YYYY-MM-DD HH24:MI:SS'),
    TO_TIMESTAMP('2026-07-11 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
);

INSERT INTO CONTRACT (
    ID,
    CONTRACT_NO,
    CONTRACT_NAME,
    CONTRACT_TYPE,
    PARTY_A,
    PARTY_B,
    PARTY_B_CREDIT_CODE,
    CONTRACT_AMOUNT,
    AMOUNT_IN_WORDS,
    STATUS,
    VERSION_NO,
    CREATED_AT,
    UPDATED_AT
) VALUES (
    '00000000-0000-0000-0000-000000000102',
    'CT-2024-0094',
    '综合业务系统运维服务合同',
    'PROCUREMENT',
    'XX市公共服务中心',
    '北京科信技术有限公司',
    '91110108MOCK009401',
    876000.00,
    '人民币捌拾柒万陆仟元整',
    'READY_FOR_REVIEW',
    1,
    TO_TIMESTAMP('2026-07-14 00:00:00', 'YYYY-MM-DD HH24:MI:SS'),
    TO_TIMESTAMP('2026-07-14 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
);

-- ============================================
-- 2) CONTRACT_LINE_ITEM
-- ============================================

INSERT INTO CONTRACT_LINE_ITEM (
    ID,
    CONTRACT_ID,
    ITEM_KEY,
    ITEM_NAME,
    QUANTITY,
    UNIT_PRICE,
    DECLARED_AMOUNT,
    SORT_NO
) VALUES (
    '00000000-0000-0000-0000-000000001101',
    '00000000-0000-0000-0000-000000000101',
    'ITEM-1',
    '车辆GPS定位系统服务',
    1.0000,
    678000.0000,
    678000.00,
    1
);

INSERT INTO CONTRACT_LINE_ITEM (
    ID,
    CONTRACT_ID,
    ITEM_KEY,
    ITEM_NAME,
    QUANTITY,
    UNIT_PRICE,
    DECLARED_AMOUNT,
    SORT_NO
) VALUES (
    '00000000-0000-0000-0000-000000001102',
    '00000000-0000-0000-0000-000000000102',
    'ITEM-1',
    '综合业务系统年度运维服务',
    1.0000,
    876000.0000,
    876000.00,
    1
);

-- ============================================
-- 3) CONTRACT_AWARD_NOTICE
-- ============================================

INSERT INTO CONTRACT_AWARD_NOTICE (
    ID,
    CONTRACT_ID,
    NOTICE_NO,
    PROJECT_NAME,
    WINNING_BIDDER,
    WINNING_BIDDER_CREDIT_CODE,
    WINNING_AMOUNT,
    WINNING_DATE,
    SOURCE_REF,
    CREATED_AT
) VALUES (
    '00000000-0000-0000-0000-000000002101',
    '00000000-0000-0000-0000-000000000101',
    'ZB-2024-0089',
    '车辆GPS定位系统项目',
    'D信息技术公司',
    '91310000MOCK008901',
    678000.00,
    TO_DATE('2026-07-01', 'YYYY-MM-DD'),
    'roundb-fixture-pass',
    TO_TIMESTAMP('2026-07-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
);

INSERT INTO CONTRACT_AWARD_NOTICE (
    ID,
    CONTRACT_ID,
    NOTICE_NO,
    PROJECT_NAME,
    WINNING_BIDDER,
    WINNING_BIDDER_CREDIT_CODE,
    WINNING_AMOUNT,
    WINNING_DATE,
    SOURCE_REF,
    CREATED_AT
) VALUES (
    '00000000-0000-0000-0000-000000002102',
    '00000000-0000-0000-0000-000000000102',
    'ZB-2024-0094',
    '综合业务系统运维服务项目',
    '北京科信信息技术有限公司',
    '91110108MOCK009499',
    849000.00,
    TO_DATE('2026-08-01', 'YYYY-MM-DD'),
    'roundb-fixture-risk',
    TO_TIMESTAMP('2026-08-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
);

COMMIT;
