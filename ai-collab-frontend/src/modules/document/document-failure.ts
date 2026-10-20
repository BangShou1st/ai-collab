// Match only messages emitted by DocumentProcessingService.safeMessage.
// Unknown / historical text is never treated as proof of a cause or rendered raw.
const guides: Record<string, { title: string; next: string }> = {
  '文档索引暂不可用：系统尚未配置嵌入模型，请联系管理员在管理中心配置后重试': {
    title: '系统 Embedding 尚未配置',
    next: '请联系系统管理员配置系统 Embedding；配置完成后，由项目所有者或管理员重试此文档。个人 AI 设置不用于文档索引。',
  },
  '文档向量化失败，请检查模型配置后重试': {
    title: '文档向量化失败',
    next: '请系统管理员检查系统 Embedding 配置与服务；具体原因尚未确认。检查后，由项目所有者或管理员重试此文档。',
  },
  '文件存储服务暂时不可用，请重试': {
    title: '文件存储暂不可用',
    next: '由项目所有者或管理员稍后重试；若持续失败，请联系服务维护者检查文件存储。',
  },
  '文档解析失败': {
    title: '文档解析失败',
    next: '请上传者检查原文件是否可正常阅读；由项目所有者或管理员重试，或在修正文件后重新上传。',
  },
  '文档解析超时': {
    title: '文档解析超时',
    next: '由项目所有者或管理员稍后重试；若持续失败，请上传者检查文件，或联系服务维护者。',
  },
  '文档解析服务繁忙，请稍后重试': {
    title: '文档解析服务繁忙',
    next: '由项目所有者或管理员稍后重试此文档。',
  },
  'TXT 与 Markdown 必须使用 UTF-8 编码': {
    title: '文件编码不受支持',
    next: '请上传者将文件保存为 UTF-8 编码，再由项目所有者或管理员重新上传；直接重试不会修正文件编码。',
  },
  '文档文本超过安全解析上限': {
    title: '文档文本超过解析上限',
    next: '请上传者拆分文件，再由项目所有者或管理员重新上传；直接重试不会缩小文件。',
  },
}

export function documentFailureGuide(message: string | null) {
  return (message && Object.hasOwn(guides, message) ? guides[message] : undefined) ?? {
    title: '处理失败，原因暂不明确',
    next: '请联系项目所有者或管理员检查原文件并尝试单项重试；若持续失败，再联系服务维护者排查。',
  }
}
