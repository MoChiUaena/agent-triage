# 接入公开 Spring Petclinic

这次使用官方 [Spring Petclinic](https://github.com/spring-projects/spring-petclinic/tree/67643c4137eb75bfeb177b427f8459c471bdcbd8)，固定上游提交 `67643c4137eb75bfeb177b427f8459c471bdcbd8`。它包含 Spring MVC 页面、Spring Data JPA 与 H2，不是本仓库的验证样例。[v0.3 首次结果](validation/2026-09-29-petclinic.md)保留 HTTP 排查记录；[v0.4 数据库验收](validation/2026-09-30-petclinic-jpa.md)继续使用同一上游提交。

接入不修改原有业务类：POM 增加 Starter 依赖和构建摘要插件，另加入一份独立的验收配置类。以下过程使用 JDK 21、Python 3.10+，首次构建需要联网。Petclinic 保持原有 Spring Boot 3.5.0；本次使用 H2，不启动 MySQL、PostgreSQL 或真实模型。

## Windows 复现

在 Agent Triage 仓库根目录构建主项目、安装 Starter，再准备独立的公开项目目录：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -B -ntp '-DskipTests' package
.\mvnw.cmd -B -ntp -f triage-spring-boot-starter/pom.xml '-DskipTests' install

$petclinicDirectory = Join-Path $env:TEMP 'triage-public-petclinic-v04'
git clone --filter=blob:none --no-checkout https://github.com/spring-projects/spring-petclinic.git $petclinicDirectory
git -C $petclinicDirectory checkout --detach 67643c4137eb75bfeb177b427f8459c471bdcbd8
python scripts/prepare_petclinic.py $petclinicDirectory
.\mvnw.cmd -B -ntp -f "$petclinicDirectory/pom.xml" '-DskipTests' '-Dcheckstyle.skip' '-Dspring-javaformat.skip' package
python scripts/petclinic_smoke.py --project-directory $petclinicDirectory
python scripts/petclinic_smoke.py --project-directory $petclinicDirectory --jpa --base-port 18470
```

准备脚本要求指定的上游提交、官方仓库来源和干净工作区，已有改动会被拒绝，不会覆盖。原 POM 备份在公开项目的 `target/triage-original-pom.xml`；新增的[验收配置类](../verification/petclinic/PetclinicTriageConfiguration.java)仅在本机副本中使用。本次不运行上游的完整测试套件，验证的是实际应用启动和接入流程；格式检查不涉及 Agent 功能。

`petclinic_smoke.py` 默认使用 18460、18461，只启动并停止本次创建的两个进程。两个端口必须空闲；需要调整时传 `--base-port`。结果和历史写入 Agent 仓库的 `target/petclinic-smoke/`，原工作区的模型设置与历史不参与。

增加 `--keep-running` 可在验证成功后保留独立预览。首次 HTTP 检查使用 18460／18461；JPA 检查使用 18470／18471。打开 Agent，从历史选择 Petclinic HTTP 服务或数据库服务；每次验证使用新的数据目录。

## Linux 验证

将 `mvnw.cmd` 换成 `./mvnw`，设置好 JDK 21 后可运行相同步骤。仓库还提供可手动触发的 [Public Spring Petclinic acceptance](../.github/workflows/petclinic.yml) 工作流，固定同一上游提交，验证完成后检查两个进程已经停止。流程不需要模型凭据，也不上传原始运行 JSON、数据库或源码副本。

## 验证内容

Starter 的请求前缀设为 `/owners/`，只采集这组原有业务路由：

| 请求 | 次数 | 结果 |
|---|---|---|
| `GET /owners/new` | 2 | 200，新建页正常 |
| `GET /owners/1` | 2 | 200，已有主人详情正常 |
| `GET /owners/999999` | 2 | 500，原有 `findOwner` 对不存在的主人抛出异常 |

两次异常来自公开项目原有的 `@ModelAttribute` 方法，发生在详情处理方法之前。MVC 匹配到了 `showOwner(int)`，不代表它的方法体已经执行。错误位置可关联到 `OwnerController.findOwner` 的源码行；lambda 合成方法仅显示源码方法候选。

正常页与详情接口的请求窗口分开计算。这个项目没有被观察到的下游 HTTP 调用，因此两组超时次数均为零；普通业务异常返回证据不足，没有被归因成下游超时。HTTP Starter 当前仍要求填写下游身份与来源，本例使用 `unobserved-http` 和 `http://127.0.0.1:1` 作为未采集标识，不向该地址生成请求。

在独立验收目录临时增加一行源码注释并重新索引后，运行 JAR 的入口摘要与当前源码不同，该次排查停止采用调用关系；原历史保留修改前的结果。脚本在 `finally` 中恢复文件并重新索引。

v0.4.0 发布时，普通异常栈没有可核验的运行类引用，异常位置的构建摘要保持未知；当时的接入检查因此是“部分完成”。当前源码对与已选 MVC 处理类对应的异常帧使用该类的已核验构建摘要，其他类仍保持未知。MVC 入口摘要一致不能替代整套运行代码的版本证明。`--jpa` 将 SQL 执行与连接获取观测登记为另一个服务，结果与限制见[数据库验收](validation/2026-09-30-petclinic-jpa.md)。

首次本地结果见[验收记录](validation/2026-09-29-petclinic.md)，当前源码的异常摘要结果见[后续复验](validation/2026-09-30-petclinic-request-version.md)。
