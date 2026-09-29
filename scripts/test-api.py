#!/usr/bin/env python3
"""HTTP regression checks. Run only against a disposable test database.
EBCDC_TEST_URL=http://localhost:8080 python3 scripts/test-api.py
Tests create documents and preserve their audit trail for inspection.
"""
import json, os, unittest, urllib.request, urllib.error, uuid
BASE = os.environ.get('EBCDC_TEST_URL')
if not BASE:
    raise SystemExit('Set EBCDC_TEST_URL explicitly; use a backend connected to a disposable test database.')

def request(method, path, role=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if role: headers['Authorization'] = 'Bearer ' + TOKENS[role]
    req = urllib.request.Request(BASE + '/api' + path, method=method, headers=headers,
        data=json.dumps(body).encode() if body is not None else None)
    try: response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as e: response = e
    raw = response.read().decode('utf-8-sig')
    try: data = json.loads(raw)
    except ValueError: data = raw
    return response.status, data

TOKENS = {}
for role in ['metier', 'projet', 'prestataire']:
    status, data = request('POST', '/auth/login', body={'email': role+'@demo.fr', 'password': 'demo1234'})
    if status != 200: raise SystemExit(f'Login failed for {role}: {status}')
    TOKENS[role] = data['token']
PROVIDERS = request('GET', '/providers', 'metier')[1]
ALPHA = next(p['id'] for p in PROVIDERS if p['name'] == 'TMA Alpha')
BETA = next(p['id'] for p in PROVIDERS if p['name'] == 'TMA Beta')

class ApiTests(unittest.TestCase):
    def call(self, method, path, role='metier', body=None, expected=200):
        status, data = request(method, path, role, body)
        self.assertEqual(status, expected, f'{method} {path}: {data}')
        return data
    def doc(self, provider=ALPHA):
        return self.call('POST', '/documents', body={'title':'AUDIT '+uuid.uuid4().hex[:8],
            'docType':'EB', 'projectCode':'AUDIT', 'providerId':provider})['id']
    def action(self, doc, action, role, expected=200):
        return self.call('POST', f'/documents/{doc}/actions', role, {'action':action}, expected)
    def sent(self, provider=ALPHA):
        d = self.doc(provider)
        for a,r in [('SUBMIT','metier'),('VALIDATE','projet'),('SEND','projet')]: self.action(d,a,r)
        return d
    def question(self, d, text='Quelle date de livraison ?'):
        return self.call('POST',f'/documents/{d}/clarifications','prestataire',{'question':text})['id']
    def test_authentication(self):
        self.call('GET','/documents',None,expected=401)
    def test_bad_password(self):
        self.call('POST','/auth/login',None,{'email':'metier@demo.fr','password':'wrong'},401)
    def test_full_workflow_two_questions(self):
        d=self.sent(); q1=self.question(d); q2=self.question(d,'Quel format ?')
        self.call('POST',f'/clarifications/{q1}/resolve','metier',{'resolution':'Le 30 novembre'})
        self.assertEqual(self.call('GET',f'/documents/{d}')['state'],'CLARIFICATION_DEMANDEE')
        self.call('POST',f'/clarifications/{q2}/resolve','projet',{'resolution':'PDF'})
        self.assertEqual(self.call('GET',f'/documents/{d}')['state'],'TRANSMIS_PRESTATAIRE')
        self.action(d,'ACCEPT','prestataire'); self.action(d,'CLOSE','projet')
        timeline=self.call('GET',f'/documents/{d}/timeline')
        self.assertEqual([t['action'] for t in timeline],['CREATE','SUBMIT','VALIDATE','SEND','REQUEST_CLARIFICATION','RESOLVE','ACCEPT','CLOSE'])
    def test_correction_and_version(self):
        d=self.doc(); self.action(d,'SUBMIT','metier'); self.action(d,'REQUEST_CORRECTION','projet')
        self.assertEqual(self.call('PUT',f'/documents/{d}/versions',body={'content':'## Contexte\nVersion corrigée'})['versionNum'],2)
        self.action(d,'SUBMIT','metier')
    def test_wrong_role_and_invalid_transition(self):
        d=self.doc(); self.action(d,'SUBMIT','projet',403); self.action(d,'CLOSE','projet',409)
        self.assertEqual(len(self.call('GET',f'/documents/{d}/timeline')),1)
    def test_provider_isolation(self):
        draft=self.doc(); other=self.sent(BETA); visible=self.sent()
        docs=self.call('GET','/documents','prestataire')
        ids=[d['id'] for d in docs]
        self.assertNotIn(draft,ids); self.assertNotIn(other,ids); self.assertIn(visible,ids)
        for d in [draft,other]:
            for suffix in ['', '/timeline','/clarifications']:
                self.call('GET',f'/documents/{d}{suffix}','prestataire',expected=404)
        self.action(other,'ACCEPT','prestataire',403)
    def test_internal_endpoints(self):
        for path in ['/analytics/providers','/analytics/correlation','/analytics/alerts','/analytics/providers.csv','/rag/corpus']:
            self.call('GET',path,'prestataire',expected=403)
            self.call('GET',path,'projet')
    def test_draft_deletion_and_retention(self):
        d=self.doc(); self.call('DELETE',f'/documents/{d}','projet',expected=403)
        self.call('DELETE',f'/documents/{d}'); self.call('GET',f'/documents/{d}',expected=404)
        d=self.doc(); self.action(d,'SUBMIT','metier'); self.call('DELETE',f'/documents/{d}',expected=409)
    def test_cannot_skip_questions(self):
        d=self.sent(); self.question(d); self.action(d,'RESOLVE','projet',409)
        self.assertEqual(self.call('GET',f'/documents/{d}')['state'],'CLARIFICATION_DEMANDEE')
    def test_cannot_create_empty_cycle(self):
        d=self.sent(); self.action(d,'REQUEST_CLARIFICATION','prestataire',400)
    def test_no_questions_after_acceptance(self):
        d=self.sent(); self.action(d,'ACCEPT','prestataire')
        self.call('POST',f'/documents/{d}/clarifications','prestataire',{'question':'Too late'},409)
    def test_no_questions_after_closure(self):
        d=self.sent(); self.action(d,'ACCEPT','prestataire'); self.action(d,'CLOSE','projet')
        self.call('POST',f'/documents/{d}/clarifications','prestataire',{'question':'Too late'},409)
    def test_no_send_without_provider(self):
        d=self.doc(None); self.action(d,'SUBMIT','metier'); self.action(d,'VALIDATE','projet'); self.action(d,'SEND','projet',409)
    def test_blank_input(self):
        self.call('POST','/documents',body={'title':'  ','docType':'EB'},expected=400)
    def test_blank_question(self):
        d=self.sent(); self.call('POST',f'/documents/{d}/clarifications','prestataire',{'question':'  '},400)
    def test_blank_answer(self):
        d=self.sent(); q=self.question(d)
        self.call('POST',f'/clarifications/{q}/resolve','projet',{'resolution':'  '},400)
    def test_missing_job_document(self):
        self.call('POST','/rag/jobs',body={'documentId':str(uuid.uuid4()),'jobType':'COMPLETENESS_CHECK'},expected=404)
    def test_concurrent_questions_and_answers(self):
        from concurrent.futures import ThreadPoolExecutor
        d=self.sent()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda n: request('POST',f'/documents/{d}/clarifications','prestataire',{'question':f'Question parallèle {n}'}),range(2)))
        self.assertTrue(all(status==200 for status,_ in results),results)
        ids=[body['id'] for _,body in results]
        with ThreadPoolExecutor(max_workers=2) as pool:
            answers=list(pool.map(lambda q: request('POST',f'/clarifications/{q}/resolve','projet',{'resolution':'Réponse parallèle'}),ids))
        self.assertTrue(all(status==200 for status,_ in answers),answers)
        timeline=self.call('GET',f'/documents/{d}/timeline')
        self.assertEqual(sum(t['action']=='REQUEST_CLARIFICATION' for t in timeline),1)
        self.assertEqual(sum(t['action']=='RESOLVE' for t in timeline),1)
        self.assertEqual(self.call('GET',f'/documents/{d}')['state'],'TRANSMIS_PRESTATAIRE')
    def test_concurrent_versions(self):
        from concurrent.futures import ThreadPoolExecutor
        d=self.doc()
        with ThreadPoolExecutor(max_workers=3) as pool:
            results=list(pool.map(lambda n: request('PUT',f'/documents/{d}/versions','metier',{'content':f'Version concurrente {n}'}),range(3)))
        self.assertTrue(all(status==200 for status,_ in results),results)
        self.assertEqual(sorted(body['versionNum'] for _,body in results),[2,3,4])
    def test_duplicate_answer_rejected(self):
        d=self.sent(); q=self.question(d)
        self.call('POST',f'/clarifications/{q}/resolve','projet',{'resolution':'Réponse'})
        self.call('POST',f'/clarifications/{q}/resolve','projet',{'resolution':'Autre réponse'},409)
    def test_null_content(self):
        d=self.doc(); self.call('PUT',f'/documents/{d}/versions',body={'content':None},expected=400)

if __name__ == '__main__': unittest.main(verbosity=2)
