-- Keep contract launch methods separate from product service modes.
-- Platform-provided templates are part of template launch and are not a third method.
SET @contract_launch_method_answer = '您好，根据点签平台的设置，发起合同主要有两种方式：\n\n1. 上传文件发起：您事先将合同内容填写完成后，直接上传合同文件进行发起。\n\n2. 模板发起：将企业内部的合同模板在 PC 端上传至点签平台，设定模板内应签署的区域后进行保存。发起合同时，填写合同相应的接收方信息后可直接发起合同。\n\n合同盖章完成后，即时生效，双方各持一份，并可随时进行查阅及下载存档。';

INSERT INTO bot_knowledge_item (
    category_id, question, answer, keywords, status, hit_count
)
SELECT
    0,
    '发起合同有几种方式？',
    @contract_launch_method_answer,
    '发起合同,合同发起,发起方式,上传文件发起,模板发起',
    1,
    0
WHERE NOT EXISTS (
    SELECT 1
    FROM bot_knowledge_item
    WHERE question IN ('发起合同有几种方式？', '发起合同有几种方式?', '合同发起有几种方式？', '合同发起有几种方式?')
      AND deleted = 0
);

UPDATE bot_knowledge_item
SET answer = @contract_launch_method_answer,
    keywords = '发起合同,合同发起,发起方式,上传文件发起,模板发起',
    status = 1,
    update_time = CURRENT_TIMESTAMP
WHERE question IN ('发起合同有几种方式？', '发起合同有几种方式?', '合同发起有几种方式？', '合同发起有几种方式?')
  AND deleted = 0;

-- Correct the obsolete wording if it exists inside an older broad FAQ answer.
UPDATE bot_knowledge_item
SET answer = REPLACE(
        REPLACE(answer,
            '发起合同有三种方式。',
            '发起合同主要有两种方式。'),
        '\n3、平台通用模板发起：点签平台内有预置的标准化合同模板（如劳动合同、采购协议等），快速发起签署流程的功能，用户仅需填写关键信息并设置签署方，即可批量生成电子合同，省去重复上传文件的繁琐步骤。可直接使用平台自带的模板发起，进入工作台-合同模板发起-合同通用模板内进行发起',
        '')
WHERE deleted = 0
  AND (answer LIKE '%发起合同有三种方式。%'
       OR answer LIKE '%3、平台通用模板发起：%');
