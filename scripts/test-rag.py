#!/usr/bin/env python3
"""Live Ollama smoke test; requires EBCDC_TEST_URL pointing at a disposable DB.
No AI quality benchmark is implied by a passing smoke test.
"""
import importlib.util, pathlib, json, time, urllib.request
spec=importlib.util.spec_from_file_location('api_tests',pathlib.Path(__file__).with_name('test-api.py'))
a=importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
def call(method,path,body=None):
    status,data=a.request(method,path,'metier',body)
    assert status==200,(path,status,data)
    return data
source='## Contraintes\nLe portail Orion doit exporter les factures au format CSV UTF-8 avec séparateur point-virgule. Chaque facture comporte un identifiant unique, une date et un montant en dirhams. Seuls les comptables autorisés peuvent exporter les données.'
r=call('POST','/rag/ingest-text',{'label':'AUDIT-Orion','content':source})
assert r['chunksInserted']>=1,r
print('PASS ingestion texte + embeddings + insertion pgvector',flush=True)
r=call('POST','/rag/search',{'query':'exporter des factures CSV avec identifiant et montant'})
assert any(h['source_label']=='AUDIT-Orion' for h in r),r
print('PASS recherche sémantique : source attendue retrouvée',flush=True)
# Same extraction endpoint for simple Markdown and plain text files.
for name in ['audit.txt','audit.md']:
    boundary='ebcdc-audit-boundary'
    data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n{source}\r\n--{boundary}--\r\n').encode()
    req=urllib.request.Request(a.BASE+'/api/rag/ingest',data=data,headers={'Authorization':'Bearer '+a.TOKENS['metier'],'Content-Type':'multipart/form-data; boundary='+boundary})
    with urllib.request.urlopen(req,timeout=120) as response:
        result=json.load(response); assert result['chunksInserted']>=1,result
    print('PASS extraction fichier '+name,flush=True)
doc=call('POST','/documents',{'title':'AUDIT RAG Orion','docType':'CDC','providerId':a.ALPHA})['id']
call('PUT',f'/documents/{doc}/versions',{'content':source})
jobs=[]
for kind in ['COMPLETENESS_CHECK','SUGGESTION']:
    j=call('POST','/rag/jobs',{'documentId':doc,'jobType':kind,'sectionTitle':'Contraintes','draft':'Exporter les factures.'})
    jobs.append((kind,j['id']))
print('Jobs soumis : '+json.dumps(jobs),flush=True)
results={}
for kind,jid in jobs:
    deadline=time.monotonic()+900
    while time.monotonic()<deadline:
        j=call('GET','/rag/jobs/'+jid)
        if j['status'] in ['DONE','FAILED']: break
        time.sleep(4)
    assert j['status']=='DONE',j
    r=j['result']
    if kind=='COMPLETENESS_CHECK':
        assert r['version_analysee']==2,r
        assert r['sections_analysees']==1,r
        assert 0<=r['sections'][0]['analyse']['score_completude']<=10,r
    else:
        assert len(r['resultat']['suggestions'])>=1,r
        assert r['extraits_utilises'],r
        assert all(isinstance(s.get('texte'),str) and s['texte'].strip() for s in r['resultat']['suggestions']),r
    results[kind]=j
    print('PASS '+kind+' : DONE, structure du résultat valide',flush=True)
output=pathlib.Path('/tmp/ebcdc-audit-20260909/rag-results.json')
output.parent.mkdir(parents=True,exist_ok=True); output.write_text(json.dumps(results,ensure_ascii=False,indent=2))
print('Résultats : '+str(output),flush=True)
