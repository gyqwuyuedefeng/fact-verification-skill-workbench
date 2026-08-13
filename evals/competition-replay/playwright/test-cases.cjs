const path = require('node:path')

const demoMaterialRoot = path.resolve(__dirname, '..', '..', 'demo-materials')

module.exports = {
  textFacts: `请逐条核验以下材料中的明确企业事实，只能使用企业证据工具，不要使用模型记忆：

科大讯飞股份有限公司统一社会信用代码为 91340000711771143J。
科大讯飞股份有限公司 2024 年营业收入为 23343093018.69 元。
科大讯飞股份有限公司 2024 年不存在任何行政处罚。`,
  financeConflict: `请核验以下企业材料中的财务主张，必须对齐主体、年度、指标口径和金额单位：

科大讯飞股份有限公司 2020 年营业收入为 100 亿元。`,
  markdown: {
    file: path.join(demoMaterialRoot, '01-模拟星河智造经营简报.md'),
    prompt: `请逐条核验附件中的工商、财务、知识产权、风险和股权关系主张。
查不到外部记录时必须输出证据不足，不得把附件自身内容当作外部证据。`,
  },
  textAmbiguity: {
    file: path.join(demoMaterialRoot, '02-同名主体核验.txt'),
    prompt: `请先确认附件中的企业主体能否唯一定位。
如果名称、统一社会信用代码或注册地址不能对应同一主体，停止后续财务、专利和风险查询，并要求人工确认。`,
  },
  csv: {
    file: path.join(demoMaterialRoot, '03-模拟企业经营指标.csv'),
    prompt: `请重点核验附件最后一行的三项主张。
保留年份、万元单位和表格单元格位置；比较营业收入时，将 15100 万元统一换算为 1.51 亿元。`,
  },
  word: {
    file: path.join(demoMaterialRoot, '06-模拟企业尽调材料.docx'),
    prompt: `请逐条核验附件中的工商、财务和风险主张，并保留 Word 段落位置。
该企业是模拟主体，不能把附件内容当作外部证据。`,
  },
  powerpoint: {
    file: path.join(demoMaterialRoot, '07-模拟企业融资说明.pptx'),
    prompt: `请逐条核验附件第二页的企业编码、营业收入和专利主张，并保留幻灯片页码。
该企业是模拟主体，外部无法定位时必须输出证据不足。`,
  },
  excel: {
    file: path.join(demoMaterialRoot, '08-模拟企业财务台账.xlsx'),
    prompt: `请核验附件 2025 年一行的营业收入和研发投入主张，保留工作表、单元格、万元单位和公式文本。
该企业是模拟主体，不能用表格自身证明外部事实。`,
  },
  pdf: {
    file: path.join(demoMaterialRoot, '06-模拟企业尽调材料.pdf'),
    prompt: `请逐条核验附件中的工商、财务和风险主张，并保留 PDF 页码。
该企业是模拟主体，不能把附件内容当作外部证据。`,
  },
}
