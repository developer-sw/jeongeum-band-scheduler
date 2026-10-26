// Disposable local PostgreSQL engines only. No Docker socket/host-data mount or real AWS/key access.
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { lstat, mkdir, readFile, writeFile } from "node:fs/promises";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repository=path.resolve(path.dirname(fileURLToPath(import.meta.url)),"..");
const suffix=randomBytes(6).toString("hex"),label="jeongeum.operational-qa";
const directory=path.join(repository,".local",`operational-backup-qa-${suffix}`);
const pgImage="postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";
const pythonImage="docker.io/library/python:3.13-slim-bookworm@sha256:4cea12ab7d78a3312c85f8cab19b6971d4fe9704da25443986c0bc20a6678850";
const ageDirectory=path.join(repository,".local/tools/age-linux-1.3.2/age");
const ownedContainers=new Map(),ownedVolumes=new Set(),checks=[];
let diagnosticNumber=0;
const report={passed:false,checks,cleanupVerified:false,actualAwsAccessed:false,developmentDatabaseAccessed:false,actualRecoveryIdentityUsed:false,
  scope:"Actual PostgreSQL tools/snapshots and age; self-container Docker shim and local versioned AWS simulator",automaticScheduleScope:"Bounded local periodic callbacks; production systemd activation not performed"};
const fail=code=>{throw Object.assign(new Error(code),{qaCode:code});};
const need=(condition,code)=>{if(!condition)fail(code);};
const check=(code,condition=true)=>{need(condition,code);checks.push(code);};
function docker(args,{input,allowFailure=false,timeout=120000,maxBuffer=192*1024*1024}={}){
  const env={...process.env};
  for(const name of Object.keys(env))if(/^(APP_|JEONGEUM_|POSTGRES_|VAPID_|COMPOSE_|AWS_)/.test(name))delete env[name];
  const result=spawnSync("docker",args,{cwd:repository,env,input,encoding:null,timeout,maxBuffer,windowsHide:true});
  if(result.status!==0&&!allowFailure){
    try{writeFileSync(path.join(directory,`diagnostic-stderr-${++diagnosticNumber}.private.log`),result.stderr??Buffer.alloc(0),{mode:0o600,flag:"wx"});}catch{}
    report.failedDockerVerb=/^[a-z-]+$/.test(args[0]??"")?args[0]:"unknown";
    report.failedProgram=args.find(value=>["tar","ldconfig","psql","python3","sh","cat"].includes(value))??"docker";
    report.failedExitCode=Number.isSafeInteger(result.status)?result.status:null;
    try{const value=JSON.parse((result.stdout??Buffer.alloc(0)).toString("utf8"));if(value.passed===false&&/^[A-Z][A-Z0-9_]+$/.test(value.error??"")){report.subprocessError=value.error;if(/^[A-Za-z][A-Za-z0-9_]+$/.test(value.failureCode??""))report.subprocessFailureCode=value.failureCode;if(/^[a-z][a-z0-9-]+$/.test(value.failureStage??""))report.subprocessFailureStage=value.failureStage;if(/^[A-Za-z]+$/.test(value.exceptionType??""))report.subprocessExceptionType=value.exceptionType;if(Number.isSafeInteger(value.errno))report.subprocessErrno=value.errno;if(["age","age-keygen"].includes(value.process?.program)){report.subprocessProgram=value.process.program;if(Number.isSafeInteger(value.process.returnCode))report.subprocessReturnCode=value.process.returnCode;}if(Number.isSafeInteger(value.completedChecks))report.subprocessCompletedChecks=value.completedChecks;}}catch{}
  }
  need(!result.error&&(allowFailure||result.status===0),"QUALIFICATION_DOCKER_FAILED");
  return {status:result.status,stdout:result.stdout??Buffer.alloc(0),stderr:result.stderr??Buffer.alloc(0)};
}
const text=args=>docker(args).stdout.toString("utf8").trim();
const json=args=>JSON.parse(text(args));
function guard(id){
  need(ownedContainers.has(id),"UNOWNED_CONTAINER_REJECTED");
  const row=json(["inspect",id])[0],name=ownedContainers.get(id);
  need(row.Id===id&&row.Name==="/"+name&&row.Config.Labels?.[label]===suffix,"CONTAINER_BINDING_REJECTED");
  need(!row.HostConfig.Privileged&&row.HostConfig.NetworkMode==="none"&&!row.HostConfig.Binds?.length&&Object.keys(row.HostConfig.PortBindings??{}).length===0,"CONTAINER_ISOLATION_REJECTED");
  for(const mount of row.Mounts)need(mount.Type==="tmpfs"&&mount.Destination==="/qa"||mount.Type==="volume"&&ownedVolumes.has(mount.Name)&&mount.Destination==="/var/lib/postgresql/data","EXISTING_OR_UNEXPECTED_MOUNT_REJECTED");
  return row;
}
function create(name,options,image,command=[]){
  need(name.endsWith(suffix)||name.includes(suffix+"-"),"RESOURCE_NAME_REJECTED");
  need(docker(["inspect",name],{allowFailure:true}).status!==0,"RESOURCE_ALREADY_EXISTS");
  const id=text(["create","--name",name,"--label",`${label}=${suffix}`,"--network","none",...options,image,...command]);
  need(/^[a-f0-9]{64}$/.test(id),"CONTAINER_ID_REJECTED");ownedContainers.set(id,name);guard(id);return id;
}
function volume(name){
  need(name.startsWith("jeongeum-ops-")&&name.includes(suffix),"VOLUME_NAME_REJECTED");
  need(docker(["volume","inspect",name],{allowFailure:true}).status!==0,"VOLUME_ALREADY_EXISTS");
  text(["volume","create","--label",`${label}=${suffix}`,name]);ownedVolumes.add(name);
  const info=json(["volume","inspect",name])[0];need(info.Name===name&&info.Driver==="local"&&info.Labels?.[label]===suffix,"VOLUME_BINDING_REJECTED");return name;
}
function exec(id,args,input){guard(id);return docker(["exec",...(input? ["-i"]:[]),"--user","root",id,...args],{input});}
async function ready(id){
  for(let i=0;i<80;i++){const row=guard(id);need(row.State.Running,"QUALIFICATION_PG_STOPPED");if(row.State.Health?.Status==="healthy")return;await new Promise(resolve=>setTimeout(resolve,250));}
  fail("QUALIFICATION_PG_TIMEOUT");
}
function inspection(id){
  const row=guard(id);return {Id:row.Id,State:{Running:row.State.Running,Health:{Status:row.State.Health?.Status}},Config:{Image:row.Config.Image,Labels:row.Config.Labels},
    HostConfig:{NetworkMode:row.HostConfig.NetworkMode,Privileged:row.HostConfig.Privileged,PortBindings:row.HostConfig.PortBindings,PublishAllPorts:row.HostConfig.PublishAllPorts},
    NetworkSettings:{Ports:row.NetworkSettings.Ports},Mounts:row.Mounts.map(m=>({Type:m.Type,Name:m.Name,Destination:m.Destination,RW:m.RW}))};
}
function seed(database,owner,source){
  let sql=`CREATE ROLE ${owner} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'qualification-only-password';\nALTER DATABASE ${database} OWNER TO ${owner};\nALTER SCHEMA public OWNER TO ${owner};\nREVOKE ALL ON DATABASE ${database} FROM PUBLIC;\nGRANT CONNECT ON DATABASE ${database} TO ${owner};\nSET ROLE ${owner};\n`;
  if(source){
    sql+="CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY,version varchar(50),description text,type text,script text,checksum integer,installed_by text,installed_on timestamp DEFAULT CURRENT_TIMESTAMP,execution_time integer,success boolean);\n";
    sql+="INSERT INTO flyway_schema_history SELECT n,n::text,'qualification fixture','SQL','fixture',n,'qualification',CURRENT_TIMESTAMP,1,true FROM generate_series(1,12) n;\n";
    for(let i=1;i<=38;i++)sql+=`CREATE TABLE qa_table_${i}(id bigint PRIMARY KEY,payload text); INSERT INTO qa_table_${i} VALUES (${i},'synthetic-row-${i}');\n`;
    for(let i=1;i<=29;i++)sql+=`CREATE SEQUENCE qa_sequence_${i}; SELECT setval('qa_sequence_${i}',${i+3},${i%2===0?"true":"false"});\n`;
  }
  return Buffer.from(sql);
}
function baseline(){
  const ids=text(["ps","-aq"]).split(/\s+/).filter(Boolean),rows=ids.length?json(["inspect",...ids]):[];
  return {containers:rows.map(r=>({id:r.Id,name:r.Name,image:r.Image,state:r.State.Status,started:r.State.StartedAt,restart:r.HostConfig.RestartPolicy,mounts:r.Mounts.map(m=>({type:m.Type,name:m.Name,target:m.Destination,rw:m.RW}))})).sort((a,b)=>a.id.localeCompare(b.id)),volumes:text(["volume","ls","-q"]).split(/\s+/).filter(Boolean).sort(),networks:text(["network","ls","-q"]).split(/\s+/).filter(Boolean).sort()};
}
let before;
let stage="initial-checks";
try{
  need(process.argv.length===2,"ARGUMENTS_REJECTED");
  need(!process.env.DOCKER_HOST&&!process.env.DOCKER_CONTEXT&&!process.env.DOCKER_TLS_VERIFY,"REMOTE_DOCKER_ENV_REJECTED");
  const context=text(["context","show"]);need(["desktop-linux","default"].includes(context),"DOCKER_CONTEXT_REJECTED");
  const endpoint=json(["context","inspect",context])[0]?.Endpoints?.docker?.Host;
  need(typeof endpoint==="string"&&/^(unix:\/\/\/|npipe:\/\/\/\/\.\/pipe\/)/.test(endpoint),"REMOTE_ENDPOINT_REJECTED");
  need(text(["info","--format","{{.OSType}}"])==="linux","LINUX_DAEMON_REQUIRED");
  try{await mkdir(path.join(repository,".local"),{mode:0o700});}catch(error){if(error.code!=="EEXIST")throw error;}
  const parent=await lstat(path.join(repository,".local"));need(parent.isDirectory()&&!parent.isSymbolicLink(),"QA_PARENT_REJECTED");
  await mkdir(directory,{mode:0o700});before=baseline();await writeFile(path.join(directory,"before.private.json"),JSON.stringify(before),{mode:0o600,flag:"wx"});
  for(const image of [pgImage,pythonImage]){if(docker(["image","inspect",image],{allowFailure:true}).status!==0)docker(["pull","--platform","linux/amd64",image],{timeout:300000});const info=json(["image","inspect",image])[0];need(info.Os==="linux"&&info.Architecture==="amd64","IMAGE_PLATFORM_REJECTED");}
  for(const executable of ["age","age-keygen"]){const info=await lstat(path.join(ageDirectory,executable));need(info.isFile()&&!info.isSymbolicLink(),"OFFICIAL_AGE_BINARY_REQUIRED");}
  check("local_linux_context_and_fixed_images");
  stage="export-public-python-runtime";
  const tools=create(`jeongeum-ops-tools-${suffix}`,["--memory","256m","--entrypoint","tar"],pythonImage,["-chf","-","-C","/usr/local","."]);
  guard(tools);const runtime=docker(["start","-a",tools]).stdout;need(text(["wait",tools])==="0"&&runtime.length>1024*1024,"PYTHON_RUNTIME_EXPORT_FAILED");
  const images={BACKEND_IMAGE:"ghcr.io/developer-sw/jeongeum-band-scheduler/backend@sha256:7dd69db8f16bfb7282b4bed2a2ccf41b58f2e8f59a659e10f9a9ab82510e0a61",FRONTEND_IMAGE:"ghcr.io/developer-sw/jeongeum-band-scheduler/frontend@sha256:9957a66e491dfbba8d84a119015ab323c4fbaa95ced16381e42e7692732ca687",CADDY_IMAGE:"ghcr.io/developer-sw/jeongeum-band-scheduler/caddy@sha256:9876276ae2e2de72212e1028c5b3cba74a99ee32e7ce17dd7f0a7d4c2000cb5c",POSTGRES_IMAGE:pgImage};
  const engines=[];
  for(const kind of ["source","target"]){
    stage=kind+"-engine-setup";
    const project=`jeongeum-ops-${kind==="source"?"qa":"restore"}-${suffix}`,database=`${kind==="source"?"opsqa":"restore"}_${suffix}`,owner=database;
    const dataVolume=volume(project+"_postgres_data"),password=randomBytes(24).toString("base64url");
    const id=create(project+"-postgres",["--label",`com.docker.compose.project=${project}`,"--label","com.docker.compose.service=postgres","--memory","512m","--mount",`type=volume,source=${dataVolume},target=/var/lib/postgresql/data`,"--tmpfs","/qa:rw,exec,nosuid,size=128m,mode=0700","--env",`POSTGRES_DB=${database}`,"--env",`POSTGRES_PASSWORD=${password}`,"--env","POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256","--health-cmd",`pg_isready -q -h 127.0.0.1 -U postgres -d ${database}`,"--health-interval","1s","--health-retries","30"],pgImage);
    text(["start",id]);await ready(id);
    stage=kind+"-extract-python-runtime";
    exec(id,["tar","-xf","-","-C","/usr/local"],runtime);
    stage=kind+"-refresh-library-cache";
    exec(id,["ldconfig"]);
    stage=kind+"-create-private-qa-directories";
    exec(id,["sh","-c","mkdir -p /qa/bin /qa/scripts; chmod 0700 /qa /qa/bin /qa/scripts"]);
    stage=kind+"-copy-public-scripts";
    for(const script of ["production-backup.py","operational-backup.py","operational-restore.py","operational-backup-qa.py"]){exec(id,["sh","-c",`cat > /qa/scripts/${script}; chmod 0600 /qa/scripts/${script}`],await readFile(path.join(repository,"scripts",script)));}
    stage=kind+"-copy-official-age-tools";
    for(const executable of ["age","age-keygen"]){exec(id,["sh","-c",`cat > /qa/bin/${executable}; chmod 0555 /qa/bin/${executable}`],await readFile(path.join(ageDirectory,executable)));}
    exec(id,["sh","-c","chmod 0555 /qa/bin/age /qa/bin/age-keygen"]);
    const binding={kind,project,container_id:id,data_volume:dataVolume,database,application_owner:owner,postgres_image:pgImage,suffix,images,source_sha:"a00a791b069f20943c5e956968e970bf138fa3dc",inspect:inspection(id)};
    stage=kind+"-write-exact-qa-binding";
    const bindingFile=path.join(directory,kind+"-binding.private.json");await writeFile(bindingFile,JSON.stringify(binding),{mode:0o600,flag:"wx"});exec(id,["sh","-c","cat > /qa/binding.json; chmod 0600 /qa/binding.json"],Buffer.from(JSON.stringify(binding)));
    exec(id,["sh","-c","chmod 0600 /qa/binding.json"]);
    stage=kind+"-seed-synthetic-database-fixture";
    guard(id);docker(["exec","-i","--user","postgres",id,"psql","-X","-qAt","--set","ON_ERROR_STOP=1","--username","postgres","--dbname",database],{input:seed(database,owner,kind==="source")});
    engines.push({id,kind});
  }
  check("independent_named_volumes_and_network_none_engines");
  const source=engines[0].id,target=engines[1].id;
  stage="source-backup-qualification";
  exec(source,["python3","-I","-B","/qa/scripts/operational-backup-qa.py"]);
  guard(source);const sourceReport=json(["exec",source,"cat","/qa/result.json"]);need(sourceReport.passed,"SOURCE_QUALIFICATION_FAILED");report.source=sourceReport;
  for(const [from,to] of [["export.age","incoming.age"],["identity.txt","identity.txt"],["source-envelope.json","source-envelope.json"]]){
    stage="independent-cipher-transfer";
    guard(source);const bytes=docker(["exec",source,"cat","/qa/"+from]).stdout;
    exec(target,["sh","-c",`cat > /qa/${to}; chmod 0600 /qa/${to}`],bytes);
  }
  check("verified_cipher_and_only_synthetic_identity_transferred_to_new_engine");
  stage="target-independent-restore-qualification";
  exec(target,["python3","-I","-B","/qa/scripts/operational-backup-qa.py"]);
  const targetReport=json(["exec",target,"cat","/qa/result.json"]);need(targetReport.passed,"TARGET_QUALIFICATION_FAILED");report.target=targetReport;
  check("source_and_independent_restore_qualification_passed");report.passed=true;
}catch(error){
  report.failureStage=stage;report.failureCode=error.qaCode??"UNEXPECTED_QUALIFICATION_ERROR";
  let index=0;
  for(const [id] of ownedContainers){try{guard(id);const diagnostic=docker(["exec",id,"cat","/qa/qualification-subprocess-stderr.private.log"],{allowFailure:true});if(diagnostic.status===0)writeFileSync(path.join(directory,`inner-subprocess-stderr-${++index}.private.log`),diagnostic.stdout,{mode:0o600,flag:"wx"});}catch{}}
}
finally{
  let cleanup=true;
  for(const [id] of [...ownedContainers].reverse()){try{guard(id);text(["rm","-f",id]);ownedContainers.delete(id);}catch{cleanup=false;}}
  for(const name of [...ownedVolumes]){try{const info=json(["volume","inspect",name])[0];need(info.Name===name&&info.Labels?.[label]===suffix,"VOLUME_CLEANUP_REJECTED");text(["volume","rm",name]);ownedVolumes.delete(name);}catch{cleanup=false;}}
  report.cleanupVerified=cleanup&&ownedContainers.size===0&&ownedVolumes.size===0;
  try{if(before){const after=baseline();report.existingDockerMetadataUnchanged=JSON.stringify(after)===JSON.stringify(before);report.cleanupVerified&&=report.existingDockerMetadataUnchanged;}await writeFile(path.join(directory,"result.private.json"),JSON.stringify(report,null,2)+"\n",{mode:0o600,flag:"wx"});}catch{report.cleanupVerified=false;}
  report.passed&&=report.cleanupVerified;console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;
}
