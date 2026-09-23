(function () {
  function mutable(task) {
    return task && !task.publishedAt && ['draft', 'importing', 'ready'].includes(task.status)
      && !['pending','running','queued'].includes(task.latestJob?.status);
  }
  function fingerprint(task) {
    return JSON.stringify((task.uploads || []).map(f => [f.id, f.kind, f.sha256]).sort((a,b)=>String(a[0]).localeCompare(String(b[0]))));
  }
  function reviewedUploads(task) {
    if (!Array.isArray(task?.uploads)) throw new Error('服务端未返回导入文件版本，无法确认数据。');
    return task.uploads.map(({id,kind,sha256}) => ({id,kind,sha256}));
  }
  function sameUploads(expected, actual) {
    reviewedUploads(actual);
    if (fingerprint(expected) !== fingerprint(actual)) throw new Error('导入文件已变化，请重新解析并复核策略。');
  }
  function validReport(validation) {
    if (validation?.status !== 'ready' || !Number.isFinite(validation?.report?.counts?.error)
      || validation.report.severity === 'error' || validation.report.counts.error !== 0) {
      throw new Error('数据校验未通过，请在排班工作台处理阻断问题。');
    }
  }
  function realResponse(result) {
    if (!result || result.fallback || result.status !== 'succeeded') throw new Error('AI 未返回真实成功结果，本次操作已停止。');
    return result;
  }
  async function prepare(input, api) {
    if (!input.taskId) throw new Error('请先选择已导入本校数据的草稿任务。');
    const task = await api.get('/exam-tasks/' + encodeURIComponent(input.taskId));
    if (!mutable(task)) throw new Error('仅允许修改未发布、未运行的草稿任务。');
    const validation = await api.post('/exam-tasks/' + task.id + '/imports/validate', {});
    validReport(validation);
    sameUploads(task, validation);
    const uploads = reviewedUploads(validation);
    const parse = realResponse(await api.post('/nl-policy/parse', input));
    if (!parse.dsl || !parse.policyId || parse.solverAuthority !== 'OR_TOOLS') throw new Error('策略缺少有效的求解器约束或确认编号，未启动排班。');
    return { taskId: task.id, taskName: task.name, reviewedUploads: uploads, fingerprint: fingerprint({uploads}), validationReport: validation.report, parse };
  }
  async function submit(prepared, api) {
    if (!prepared?.parse) throw new Error('请先解析并复核策略。');
    const task = await api.get('/exam-tasks/' + encodeURIComponent(prepared.taskId));
    if (!mutable(task) || fingerprint(task) !== prepared.fingerprint) throw new Error('任务状态或导入文件已变化，请重新解析策略。');
    const validation = await api.post('/exam-tasks/' + task.id + '/imports/validate', {});
    validReport(validation);
    sameUploads({uploads:prepared.reviewedUploads}, validation);
    const job = await api.post('/exam-tasks/' + task.id + '/schedule-jobs', {policyId: prepared.parse.policyId, reviewedUploads: prepared.reviewedUploads});
    const jobId = job?.jobId || job?.runId;
    if (!jobId) throw new Error('服务端未返回排班任务编号，请刷新任务状态后重试。');
    return {source:'ai_policy', taskId:task.id, jobId, validationReport:validation.report, parse:prepared.parse, provider:prepared.parse.provider, at:Date.now()};
  }
  window.JKXTAiWorkflow = { mutable, fingerprint, realResponse, prepare, submit };
})();
