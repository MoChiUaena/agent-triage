"use strict";
(() => {
  const $=selector=>document.querySelector(selector);
  const node=(tag,text,className)=>{const value=document.createElement(tag);if(text!=null)value.textContent=text;if(className)value.className=className;return value;};
  const date=value=>new Date(value).toLocaleString("zh-CN",{hour12:false});
  const labels={PENDING:"等待前置任务",RUNNING:"执行中",SUCCEEDED:"已完成",PARTIAL:"完成但有缺口",FAILED:"失败",SKIPPED:"已跳过",CANCELLED:"已取消",PLANNED:"等待执行",COMPLETED:"任务已结束",COMPLETED_WITH_GAPS:"已结束，有缺口",TIMED_OUT:"执行超时",INTERRUPTED:"已中断"};
  const verdicts={REPLAN_REQUIRED:"需要重新生成计划",INCOMPLETE:"材料不完整",ATTENTION_REQUIRED:"有待核对的失败或提醒",REVIEW_REMAINING:"继续人工核查"};
  let repository="",version=0,watch=0,selectedRun="",pollTimer=null,plan=null,busy=false,issueReports=[];
  async function request(path,options={}){const response=await fetch(path,{cache:"no-store",...options});if(!response.ok){const problem=await response.json().catch(()=>({}));throw new Error(problem.detail||"读取工作流失败 ("+response.status+")");}return response.json();}
  const post=(path,input)=>request(path,{method:"POST",headers:{"Content-Type":"application/json","X-Triage-Development":"1"},body:JSON.stringify(input??{})});
  function message(text,error=false){$("#workflow-notice").textContent=text;$("#workflow-notice").hidden=!text;$("#workflow-notice").classList.toggle("error",error);}
  function ready(){for(const id of ["#workflow-plan-button","#workflow-pr-number","#workflow-issue-number","#workflow-issue-report","#workflow-ci-report"])$(id).disabled=busy||!repository;$("#workflow-execute").disabled=busy||!plan;$("#workflow-close-plan").disabled=busy;}
  function stopPolling(){watch++;if(pollTimer!=null)clearTimeout(pollTimer);pollTimer=null;}
  function dependencies(task){const parts=[];if(task.requires.length)parts.push("需要成功："+task.requires.join(", "));if(task.after.length)parts.push("等待结束："+task.after.join(", "));return parts.join("；")||"无前置任务";}
  function sourceButton(reference){const button=node("button",reference.title+" · "+(reference.version||"版本未读取").substring(0,10),"workflow-source");button.type="button";button.addEventListener("click",()=>openSource(reference));return button;}
  async function openSource(reference){
    const selected=repository,current=version,generation=watch,kind=reference.kind.toLowerCase();if(!["pr","issue","ci"].includes(kind))return;
    try{const report=await request("/api/development/"+kind+"/reports/"+encodeURIComponent(reference.id));if(current!==version||generation!==watch)return;if(report.repositoryId!==selected)throw new Error("报告不属于当前仓库。");({pr:window.prPanel,issue:window.issuePanel,ci:window.ciPanel})[kind]?.render(report);}
    catch(error){if(current===version&&generation===watch)message(error.message,true);}
  }
  function render(snapshot,scroll=false){
    $("#workflow-result").hidden=false;$("#workflow-result-title").textContent="PR #"+snapshot.plan.pull.number+" · "+snapshot.plan.pull.title;
    $("#workflow-result-meta").textContent=snapshot.repository+" · "+date(snapshot.createdAt)+" · Head "+snapshot.plan.pull.headSha;
    $("#workflow-state").textContent=labels[snapshot.state]||snapshot.state;$("#workflow-result-message").textContent=snapshot.message;
    const ended=snapshot.tasks.filter(task=>!["PENDING","RUNNING"].includes(task.state)).length;
    $("#workflow-progress").textContent="已结束 "+ended+" / "+snapshot.tasks.length+" 个任务";
    $("#workflow-cancel").disabled=!["PLANNED","RUNNING"].includes(snapshot.state);
    $("#workflow-tasks").replaceChildren(...snapshot.tasks.map(task=>{
      const card=node("li",null,"workflow-task "+task.state.toLowerCase()),heading=node("div",null,"workflow-task-heading");heading.append(node("strong",task.title),node("span",labels[task.state]||task.state,"workflow-task-state"));
      card.append(heading,node("p",dependencies(task),"management-caption"),node("p",task.message));
      if(task.startedAt)card.append(node("small",date(task.startedAt)+(task.finishedAt?" → "+date(task.finishedAt):""),"management-caption"));
      if(task.references.length){const refs=node("div",null,"workflow-references");refs.append(...task.references.map(sourceButton));card.append(refs);}return card;
    }));
    $("#workflow-checks").replaceChildren(...snapshot.data.checks.map(check=>node("li",({CONFLICT:"冲突",GAP:"缺口",NOTE:"范围说明",RESOLVED:"已补查"}[check.state]||check.state)+" · "+check.message,"workflow-check "+check.state.toLowerCase())));
    if(!snapshot.data.checks.length)$("#workflow-checks").append(node("li","暂未生成证据核验项。"));
    const summary=snapshot.data.summary;$("#workflow-summary").hidden=!summary;
    if(summary){$("#workflow-summary-state").textContent=verdicts[summary.verdict]||summary.verdict;$("#workflow-summary-text").textContent=summary.text;$("#workflow-summary-actions").replaceChildren(...summary.actions.map(text=>node("li",text)));$("#workflow-summary-gaps").replaceChildren(...summary.gaps.map(text=>node("li",text)));if(!summary.gaps.length)$("#workflow-summary-gaps").append(node("li","读取范围内未记录额外缺口，仍需核对完整代码和合入条件。"));$("#workflow-summary-sources").replaceChildren(...summary.sources.map(sourceButton));$("#workflow-summary-scope").textContent=summary.scope;}
    if(scroll)$("#workflow-result").scrollIntoView({behavior:"smooth",block:"start"});
  }
  function watchRun(snapshot){stopPolling();selectedRun=snapshot.id;render(snapshot,true);if(["PLANNED","RUNNING"].includes(snapshot.state))schedulePoll(snapshot.id,version,watch,0);}
  function schedulePoll(id,current,generation,failures){pollTimer=setTimeout(async()=>{pollTimer=null;if(current!==version||generation!==watch)return;try{const result=await request("/api/development/workflows/"+encodeURIComponent(id));if(current!==version||generation!==watch)return;render(result);if(["PLANNED","RUNNING"].includes(result.state))schedulePoll(id,current,generation,0);else await loadHistory();}catch(error){if(current!==version||generation!==watch)return;if(failures<2)schedulePoll(id,current,generation,failures+1);else message(error.message+" 后台可能仍在运行，可点击刷新任务状态。",true);}},failures?2000*(failures+1):1200);}
  async function openRun(id){stopPolling();const current=version,generation=watch;selectedRun=id;try{const snapshot=await request("/api/development/workflows/"+encodeURIComponent(id));if(current!==version||generation!==watch)return;if(snapshot.repositoryId!==repository)throw new Error("工作流不属于当前仓库。");watchRun(snapshot);}catch(error){if(current===version&&generation===watch)message(error.message,true);}}
  async function loadHistory(){const selected=repository,current=version;$("#workflow-history").replaceChildren();if(!selected)return;try{const values=await request("/api/development/repositories/"+selected+"/workflows");if(current!==version)return;$("#workflow-history").replaceChildren(...values.map(value=>{const button=node("button","PR #"+value.plan.pull.number+" · "+date(value.createdAt)+" · "+(labels[value.state]||value.state),"ci-history-item");button.type="button";button.addEventListener("click",()=>{if(current===version)openRun(value.id);});return button;}));if(!values.length)$("#workflow-history").append(node("p","还没有保存的协同工作流。","repository-list-empty"));}catch(error){if(current===version)$("#workflow-history").replaceChildren(node("p",error.message,"repository-error"));}}
  async function loadMaterials(){const selected=repository,current=version;issueReports=[];for(const id of ["#workflow-issue-report","#workflow-ci-report"])$(id).replaceChildren();if(!selected)return;try{const values=await Promise.all([request("/api/development/repositories/"+selected+"/issue/reports"),request("/api/development/repositories/"+selected+"/ci/reports")]);if(current!==version)return;issueReports=values[0];const issueDefault=node("option","重新生成基础分诊（不新调用模型）");issueDefault.value="";const ciDefault=node("option","自动读取同一提交的 Actions");ciDefault.value="";$("#workflow-issue-report").replaceChildren(issueDefault,...values[0].map(report=>{const option=node("option","#"+report.issue.number+" · "+date(report.createdAt)+" · "+(report.source==="MODEL"?"已有模型建议":"基础分诊"));option.value=report.id;return option;}));$("#workflow-ci-report").replaceChildren(ciDefault,...values[1].map(report=>{const option=node("option",report.workflowName+" / "+report.job.name+" · "+report.headSha.substring(0,7)+" · "+(report.source==="PASTED"?"粘贴来源":"GitHub"));option.value=report.id;return option;}));$("#workflow-issue-report").value="";$("#workflow-ci-report").value="";}catch(error){if(current===version)message(error.message,true);}}
  $("#workflow-plan-form").addEventListener("submit",async event=>{
    event.preventDefault();if(busy||!repository)return;const selected=repository,current=version,input={prNumber:Number($("#workflow-pr-number").value)};
    const issue=$("#workflow-issue-number").value;if(issue)input.issueNumber=Number(issue);if($("#workflow-issue-report").value)input.issueReportId=$("#workflow-issue-report").value;if($("#workflow-ci-report").value)input.ciReportId=$("#workflow-ci-report").value;
    busy=true;plan=null;ready();message("正在读取 PR 版本并生成执行计划…");
    try{const result=await post("/api/development/repositories/"+selected+"/workflows/plans",input);if(current!==version)return;plan=result;$("#workflow-plan-context").textContent="PR #"+result.pull.number+" "+result.pull.title+"\nBase "+result.pull.baseSha+"\nHead "+result.pull.headSha+"\nIssue "+(result.issueNumber||"没有明确引用");$("#workflow-plan-tasks").replaceChildren(...result.tasks.map(task=>node("li",task.title+" · "+dependencies(task))));$("#workflow-plan-notice").textContent=result.notice;$("#workflow-plan-dialog").showModal();message("");}
    catch(error){if(current===version)message(error.message,true);}finally{if(current===version){busy=false;ready();}}
  });
  $("#workflow-execute").addEventListener("click",async()=>{if(busy||!plan)return;const selected=repository,current=version,id=plan.id;busy=true;ready();message("正在启动工作流…");try{const result=await post("/api/development/repositories/"+selected+"/workflows",{planId:id});if(current!==version)return;plan=null;$("#workflow-plan-dialog").close();message("");watchRun(result);await loadHistory();}catch(error){if(current===version)message(error.message,true);}finally{if(current===version){busy=false;ready();}}});
  $("#workflow-close-plan").addEventListener("click",()=>{if(!busy){plan=null;$("#workflow-plan-dialog").close();ready();}});
  $("#workflow-plan-dialog").addEventListener("cancel",event=>{if(busy)event.preventDefault();else{plan=null;ready();}});
  $("#workflow-issue-report").addEventListener("change",()=>{const report=issueReports.find(value=>value.id===$("#workflow-issue-report").value);if(report)$("#workflow-issue-number").value=String(report.issue.number);});
  $("#workflow-cancel").addEventListener("click",async()=>{if(!selectedRun)return;const current=version,id=selectedRun;$("#workflow-cancel").disabled=true;try{const result=await post("/api/development/workflows/"+encodeURIComponent(id)+"/cancel");if(current!==version||id!==selectedRun)return;watchRun(result);await loadHistory();}catch(error){if(current===version)message(error.message,true);}});
  $("#workflow-refresh-state").addEventListener("click",()=>{if(selectedRun)openRun(selectedRun);});
  $("#workflow-refresh-history").addEventListener("click",()=>{loadHistory();loadMaterials();});
  window.workflowPanel={selectRepository(id){if(id===repository)return;stopPolling();repository=id;version++;selectedRun="";plan=null;busy=false;$("#workflow-result").hidden=true;$("#workflow-plan-dialog").close();message("");ready();loadHistory();loadMaterials();},openRun,render};
})();
