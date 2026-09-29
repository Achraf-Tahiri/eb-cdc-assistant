#!/usr/bin/env python3
"""Known-date SQL fixtures + HTTP assertions. Requires EBCDC_TEST_URL and EBCDC_TEST_DB.
Uses the local ebcdc-db container; never run against the original database.
"""
import os, uuid, subprocess, pathlib, importlib.util, json
spec=importlib.util.spec_from_file_location('api_tests',pathlib.Path(__file__).with_name('test-api.py'))
a=importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
db=os.environ.get('EBCDC_TEST_DB','')
if not db.startswith(('ebcdc_test','ebcdc_audit')): raise SystemExit('EBCDC_TEST_DB must name a disposable ebcdc_test* or ebcdc_audit* database')
p,d1,d2=[str(uuid.uuid4()) for _ in range(3)]
sql=f"""BEGIN;
INSERT INTO providers(id,name,contract_ref) VALUES('{p}','AUDIT métriques {p[:8]}','TEST');
INSERT INTO documents(id,title,doc_type,provider_id,current_state,created_by)
SELECT v.id,v.title,'EB','{p}','CLOTURE',u.id FROM (VALUES ('{d1}'::uuid,'AUDIT métriques A'),('{d2}'::uuid,'AUDIT métriques B')) v(id,title) CROSS JOIN users u WHERE u.email='metier@demo.fr';
"""
# Relative days: document A waits 6 then 2 days; document B waits 4 days.
rows=[(d1,0,'BROUILLON','BROUILLON','CREATE','metier'),(d1,1,'BROUILLON','SOUMIS_PROJET','SUBMIT','metier'),
(d1,2,'SOUMIS_PROJET','VALIDE_PROJET','VALIDATE','projet'),(d1,3,'VALIDE_PROJET','TRANSMIS_PRESTATAIRE','SEND','projet'),
(d1,9,'TRANSMIS_PRESTATAIRE','CLARIFICATION_DEMANDEE','REQUEST_CLARIFICATION','prestataire'),
(d1,10,'CLARIFICATION_DEMANDEE','TRANSMIS_PRESTATAIRE','RESOLVE','projet'),
(d1,12,'TRANSMIS_PRESTATAIRE','CLARIFICATION_DEMANDEE','REQUEST_CLARIFICATION','prestataire'),
# Close the clarification cycle without adding provider waiting time by using same time and serial audit order.
(d1,13,'CLARIFICATION_DEMANDEE','TRANSMIS_PRESTATAIRE','RESOLVE','metier'),
(d1,13,'TRANSMIS_PRESTATAIRE','IMPLEMENTATION_ACCEPTEE','ACCEPT','prestataire'),
(d1,14,'IMPLEMENTATION_ACCEPTEE','CLOTURE','CLOSE','projet'),
(d2,0,'BROUILLON','BROUILLON','CREATE','metier'),(d2,1,'BROUILLON','SOUMIS_PROJET','SUBMIT','metier'),
(d2,2,'SOUMIS_PROJET','VALIDE_PROJET','VALIDATE','projet'),(d2,3,'VALIDE_PROJET','TRANSMIS_PRESTATAIRE','SEND','projet'),
(d2,7,'TRANSMIS_PRESTATAIRE','IMPLEMENTATION_ACCEPTEE','ACCEPT','prestataire'),(d2,8,'IMPLEMENTATION_ACCEPTEE','CLOTURE','CLOSE','projet')]
# Separate provider identity for valid audit ownership.
sql+=f"INSERT INTO users(id,email,display_name,role,password_hash,provider_id) SELECT gen_random_uuid(),'audit-{p}@test.fr','Audit Provider','PRESTATAIRE',password_hash,'{p}' FROM users WHERE email='prestataire@demo.fr';\n"
for d,day,fr,to,act,role in rows:
 email=f'audit-{p}@test.fr' if role=='prestataire' else role+'@demo.fr'
 sql+=f"INSERT INTO workflow_transitions(document_id,from_state,to_state,action,actor_id,actor_role,occurred_at) SELECT '{d}','{fr}','{to}','{act}',id,role,'2026-01-01'::timestamptz+interval '{day} days' FROM users WHERE email='{email}';\n"
for days in [6,2,2]:
 sql+=f"INSERT INTO clarification_requests(id,document_id,provider_id,requested_by,question,days_since_provider_received,resolved_at,resolution) SELECT gen_random_uuid(),'{d1}','{p}',id,'Question audit',{days},now(),'Réponse' FROM users WHERE email='audit-{p}@test.fr';\n"
sql+=f"INSERT INTO rag_jobs(id,document_id,job_type,status,result,finished_at) VALUES(gen_random_uuid(),'{d1}','COMPLETENESS_CHECK','DONE','{{\"sections\":[{{\"analyse\":{{\"score_completude\":8}}}},{{\"analyse\":{{\"score_completude\":6}}}}]}}',now());\nCOMMIT;"
subprocess.run(['docker','exec','-i','ebcdc-db','psql','-v','ON_ERROR_STOP=1','-U','ebcdc','-d',db],input=sql,text=True,check=True,stdout=subprocess.DEVNULL)
status, metrics=a.request('GET','/analytics/providers','projet'); assert status==200
r=next(r for r in metrics if r['provider_id']==p)
# Four waiting intervals, including the zero-day interval: (6+2+0+4)/4 = 3.
expected={'nb_documents':2,'m1_attente_moyenne_jours':3,'m2_taux_tardif_pct':33.3,'m3_cycles_par_document':1,'m4_ratio_cycles_questions':0.67,'total_questions':3,'total_cycles':2}
for key,value in expected.items(): assert float(r[key])==value,(key,r[key],value)
print('PASS M1–M4 : '+json.dumps(expected))
status,breakdown=a.request('GET','/analytics/documents/'+d1,'projet'); assert status==200
b={r['acteur']:float(r['jours']) for r in breakdown}
assert b=={'METIER':1,'PROJET':2,'PRESTATAIRE':8,'PROJET_METIER (clarifications)':2,'PRESTATAIRE (implémentation)':1},b
print('PASS M5 : durées exactes, y compris transitions simultanées')
status,corr=a.request('GET','/analytics/correlation','projet'); assert status==200
assert next(r for r in corr if r['documentId']==d1)['score_completude']==7
print('PASS M6 : moyenne des scores 8 et 6 = 7')
status,csv=a.request('GET','/analytics/providers.csv','projet'); assert status==200 and 'prestataire;contrat;' in csv
print('PASS export CSV')
