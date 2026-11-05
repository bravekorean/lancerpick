"""Validate schema only in an owned, temporary MySQL 8.4 container.

Run from repository root: python docs/scripts/test_advanced_schema.py
Use --runtime for V1-V6 or --finance-v3-only for the V3 backfill check.
The default mode checks the historical full review candidate.
Does not connect to local application databases. Requires Docker and mysql:8.4.
"""
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
NAME = 'lancerpick-advanced-schema-' + secrets.token_hex(5)
PASSWORD = secrets.token_urlsafe(24)
passed = []

def docker(*args, data=None):
    return subprocess.run(['docker', *args], input=data, text=True,
                          encoding='utf-8', capture_output=True)

def db(statement, ok=True):
    r = docker('exec', '-i', '-e', 'MYSQL_PWD=' + PASSWORD, NAME,
               'mysql', '-uroot', '--default-character-set=utf8mb4',
               '--batch', '--skip-column-names', 'schema_review', data=statement)
    if ok and r.returncode:
        raise RuntimeError(r.stderr)
    if not ok and (r.returncode == 0 or not any(x in r.stderr for x in
                      ('ERROR 3819', 'ERROR 1062', 'ERROR 1452'))):
        raise AssertionError('Expected constraint failure: ' + r.stderr)
    return r.stdout.strip()

def reject(name, statement):
    db(statement, ok=False)
    passed.append(name)

try:
    r = docker('run', '--detach', '--name', NAME,
               '-e', 'MYSQL_ROOT_PASSWORD=' + PASSWORD,
               '-e', 'MYSQL_DATABASE=schema_review', 'mysql:8.4')
    if r.returncode:
        raise RuntimeError(r.stderr)
    for attempt in range(60):
        r = docker('exec', '-e', 'MYSQL_PWD=' + PASSWORD, NAME,
                   'mysql', '-uroot', '-e', 'SELECT 1')
        if r.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError('Temporary MySQL did not become ready')

    for f in ['LancerPick/src/main/resources/db/migration/V1__init.sql',
              'LancerPick/src/main/resources/db/migration/V2__add_wallet_topups.sql',
              'docs/sql/seed-manual-trade-demo.sql']:
        db((ROOT/f).read_text(encoding='utf-8-sig'))
    db("""
    INSERT INTO orders(id,buyer_id,seller_id,product_id,request_key,status,price_amount,duration_days_snapshot,created_at,updated_at)
    VALUES(10,2,1,1,'00000000-0000-0000-0000-000000000010','REQUESTED',999,1,'2026-11-01','2026-11-01');
    INSERT INTO payments(order_id,amount,fee_rate_bps,status,paid_at,refunded_at)
    VALUES(10,999,1000,'REFUNDED','2026-11-01','2026-11-02');
    INSERT INTO order_histories(order_id,actor_id,action,from_status,to_status,created_at)
    VALUES(10,2,'CREATED',NULL,'REQUESTED','2026-11-01');
    """)
    # SQL fixture intentionally isolates schema constraints, not a valid complete business flow.
    before=db('SELECT id,email,password_hash,display_name,role,created_at,updated_at FROM members ORDER BY id; SELECT * FROM wallets ORDER BY member_id;')
    if '--finance-v3-only' in sys.argv:
        db((ROOT/'LancerPick/src/main/resources/db/migration/V3__prepare_installment_finance.sql').read_text(encoding='utf-8-sig'))
        assert before==db('SELECT id,email,password_hash,display_name,role,created_at,updated_at FROM members ORDER BY id; SELECT * FROM wallets ORDER BY member_id;')
        assert db('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()')=='17'
        assert db('SELECT policy_version FROM orders WHERE id=10')=='LEGACY_V1'
        assert db('SELECT CONCAT(installment_type,":",refunded_amount) FROM payments WHERE order_id=10')=='FULL:999'
        reject('V3 refund amount cannot disagree', 'UPDATE payments SET refunded_amount=0 WHERE order_id=10')
        reject('V3 policy/part mismatch', "UPDATE payments SET installment_type='DEPOSIT' WHERE order_id=10")
        print('PASS V3: existing members/wallets preserved; 17 tables; legacy policy; refunded amount backfill; refund and installment constraints.')
        raise SystemExit(0)
    runtime = '--runtime' in sys.argv
    if runtime:
        db("INSERT INTO cancellation_requests(order_id,requested_by,request_key,reason,status,requested_at,resolved_at,resolved_by) VALUES(10,2,'00000000-0000-0000-0000-000000000999','legacy','REJECTED','2026-11-01','2026-11-01',1)")
        for name in ['V3__prepare_installment_finance.sql','V4__add_order_contracts.sql',
                     'V5__add_communication_and_identity_schema.sql','V6__add_contract_timers_and_closures.sql']:
            db((ROOT/'LancerPick/src/main/resources/db/migration'/name).read_text(encoding='utf-8-sig'))
        assert db('SELECT resolution_source FROM cancellation_requests WHERE order_id=10')=='SELLER'
        assert db('SELECT actor_type FROM order_histories WHERE order_id=10')=='MEMBER'
        passed.append('legacy cancellation and history backfill')
        assert db('SELECT COUNT(*) FROM members WHERE auth_version<>0 OR email_verified_at IS NOT NULL')=='0'
        assert db('SELECT chat_sequence FROM orders WHERE id=10')=='0'
        passed.append('legacy auth and chat defaults')
    else:
        db((ROOT/'docs/sql/advanced-db-candidate.sql').read_text(encoding='utf-8-sig'))
    assert before==db('SELECT id,email,password_hash,display_name,role,created_at,updated_at FROM members ORDER BY id; SELECT * FROM wallets ORDER BY member_id;')
    assert db('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()')=='29'
    assert db("SELECT policy_version FROM orders WHERE id=10")=='LEGACY_V1'
    assert db('SELECT CONCAT(installment_type,":",refunded_amount) FROM payments WHERE order_id=10')=='FULL:999'
    passed.extend(['legacy member/wallet preservation','29 tables','legacy policy and refund backfill'])
    reject('v2 minimum amount',"UPDATE orders SET policy_version='CONTRACT_V2' WHERE id=10")
    for i in [101,102]:
        db(f"""INSERT INTO orders(id,buyer_id,seller_id,product_id,request_key,status,price_amount,duration_days_snapshot,accepted_at,buyer_confirmed_terms_at,paid_at,due_at,created_at,updated_at,policy_version)
        VALUES({i},2,1,1,'00000000-0000-0000-0000-{i:012d}','AWAITING_CONFIRMATION',100001,1,'2026-11-01 01:00:00','2026-11-01 02:00:00','2026-11-01 02:00:00','2026-11-02 02:00:00','2026-11-01','2026-11-03','CONTRACT_V2');
        INSERT INTO deliveries(id,order_id,sequence_no,description,result_url,submitted_at) VALUES({i},{i},1,'result','https://example.com/result','2026-11-03');""")
    payment="INSERT INTO payments(order_id,amount,status,paid_at,order_policy_version,installment_type) VALUES(101,{amount},'PAID','2026-11-03','{policy}','{part}')"
    reject('FULL forbidden on V2',payment.format(amount=100001,policy='CONTRACT_V2',part='FULL'))
    reject('policy FK mismatch',payment.format(amount=100001,policy='LEGACY_V1',part='FULL'))
    db(payment.format(amount=50000,policy='CONTRACT_V2',part='DEPOSIT'))
    if runtime:
        reject('runtime keeps single payment until installment services are connected',payment.format(amount=50001,policy='CONTRACT_V2',part='BALANCE'))
    else:
        db(payment.format(amount=50001,policy='CONTRACT_V2',part='BALANCE'))
    reject('duplicate installment',payment.format(amount=50000,policy='CONTRACT_V2',part='DEPOSIT'))
    assert db('SELECT SUM(amount) FROM payments WHERE order_id=101')==('50000' if runtime else '100001')
    passed.append('payment sum with staged activation')
    contract="""INSERT INTO order_contracts(order_id,version,template_version,status,active_slot,special_terms,canonical_content,content_hash,generation_key,created_by,created_at,updated_at)
    VALUES(101,{v},'v1','DRAFT',1,'','{{}}',UNHEX(SHA2('terms',256)),'00000000-0000-0000-0000-{v:012d}',1,'2026-11-01','2026-11-01')"""
    db(contract.format(v=1))
    reject('one current contract',contract.format(v=2))
    db("UPDATE order_contracts SET status='SUPERSEDED',active_slot=NULL WHERE order_id=101")
    db(contract.format(v=2))
    reject('READY requires PDF',"UPDATE order_contracts SET status='READY' WHERE order_id=101 AND version=2")
    cid=db('SELECT id FROM order_contracts WHERE order_id=101 AND version=2')
    sig=f"INSERT INTO contract_signatures(contract_id,member_id,party_role,signed_content_hash,signed_file_hash,signed_at) VALUES({cid},2,'BUYER',UNHEX(SHA2('a',256)),UNHEX(SHA2('b',256)),'2026-11-03')"
    db(sig)
    reject('duplicate party signature',sig)
    timer="""INSERT INTO order_response_timers(order_id,delivery_id,timer_type,basis_at,eligible_at,started_by,started_at,expires_at,status,active_slot)
    VALUES({o},{d},'{kind}','2026-11-03','2026-11-05',1,'2026-11-05','2026-11-12','ACTIVE',1)"""
    reject('timer delivery must belong to order',timer.format(o=101,d=102,kind='REVIEW_RESPONSE'))
    db(timer.format(o=101,d=101,kind='REVIEW_RESPONSE'))
    reject('one active timer',timer.format(o=101,d=101,kind='BALANCE_PAYMENT'))
    tid=db('SELECT id FROM order_response_timers WHERE order_id=101')
    reject('admin stop requires reason',f"UPDATE order_response_timers SET status='CANCELLED',active_slot=NULL,ended_by=1,ended_at='2026-11-06',end_reason='ADMIN_STOP' WHERE id={tid}")
    db(f"UPDATE order_response_timers SET status='CANCELLED',active_slot=NULL,ended_by=1,ended_at='2026-11-06',end_reason='ADMIN_STOP',admin_stop_reason='review pending' WHERE id={tid}")
    reject('stopped timer cannot restart same cycle',timer.format(o=101,d=101,kind='REVIEW_RESPONSE'))
    db(timer.format(o=102,d=102,kind='REVIEW_RESPONSE'))
    tid2=db('SELECT id FROM order_response_timers WHERE order_id=102')
    reject('premature timer expiry',f"UPDATE order_response_timers SET status='EXPIRED',active_slot=NULL,ended_at='2026-11-11',end_reason='TIMEOUT' WHERE id={tid2}")
    db(f"UPDATE order_response_timers SET status='EXPIRED',active_slot=NULL,ended_at='2026-11-12',end_reason='TIMEOUT' WHERE id={tid2}")
    closure=f"""INSERT INTO order_closures(order_id,reason,source_timer_id,actor_type,paid_amount,buyer_refund_amount,seller_gross_amount,fee_amount,seller_net_amount,created_at)
    VALUES(102,'REVIEW_NO_RESPONSE',{tid2},'SYSTEM',50000,35000,15000,150,14850,'2026-11-12')"""
    db(closure)
    reject('one closure',closure)
    reject('incorrect compensation rate',closure.replace('102,','101,',1).replace(str(tid2)+",'SYSTEM'",str(tid)+",'SYSTEM'").replace('35000,15000,150,14850','30000,20000,200,19800'))
    balance=db('SELECT balance FROM wallets WHERE member_id=2')
    reject('rollback on duplicate closure', 'START TRANSACTION; UPDATE wallets SET balance=balance+35000 WHERE member_id=2;'+closure+'; COMMIT;')
    assert db('SELECT balance FROM wallets WHERE member_id=2')==balance
    passed.append('failed closure rolls back balance')
    chat="INSERT INTO order_chat_messages(order_id,sequence,sender_id,client_message_id,content,created_at) VALUES(101,1,2,'00000000-0000-0000-0000-000000000111','hello','2026-11-03')"
    db(chat)
    reject('chat request/sequence duplicate',chat)
    db("INSERT INTO order_histories(order_id,actor_type,actor_id,action,from_status,to_status,created_at) VALUES(102,'SYSTEM',NULL,'TERMINATED','AWAITING_CONFIRMATION','TERMINATED','2026-11-12')")
    reject('SYSTEM history cannot impersonate member',"INSERT INTO order_histories(order_id,actor_type,actor_id,action,from_status,to_status,created_at) VALUES(102,'SYSTEM',1,'TERMINATED','AWAITING_CONFIRMATION','TERMINATED','2026-11-12')")
    challenge="""INSERT INTO account_challenges(purpose,member_id,email,token_hash,credential_version,issued_at,expires_at,delivery_status)
    VALUES('PASSWORD_RESET',{member},'demo.b@example.com',UNHEX(SHA2('reset',256)),0,'2026-11-01','2026-11-01 00:15:00','PENDING')"""
    reject('password reset requires member',challenge.format(member='NULL'))
    db(challenge.format(member=2))
    reject('challenge hash unique',challenge.format(member=2))
    reject('invalid throttle count',"INSERT INTO auth_request_limits(scope,subject_hmac,window_start,request_count,expires_at) VALUES('MAIL',UNHEX(SHA2('email',256)),'2026-11-01',-1,'2026-11-02')")
    event='00000000-0000-0000-0000-000000000555'
    db(f"INSERT INTO outbox_events(event_id,type,schema_version,aggregate_type,aggregate_id,payload,status,next_attempt_at,created_at) VALUES('{event}','ORDER_CREATED',1,'ORDER',101,'{{}}','PENDING','2026-11-01','2026-11-01')")
    reject('processing event requires lease',f"UPDATE outbox_events SET status='PROCESSING' WHERE event_id='{event}'")
    notice=f"INSERT INTO notifications(recipient_id,event_id,type,order_id,created_at) VALUES(2,'{event}','ORDER_CREATED',101,'2026-11-01')"
    db(notice);reject('notification recipient event deduplication',notice)
    reject('notification read cannot precede creation',"UPDATE notifications SET read_at='2026-10-31'")
    db("INSERT INTO cancellation_requests(order_id,requested_by,request_key,reason,requested_at) VALUES(101,2,'00000000-0000-0000-0000-000000000666','review','2026-11-01')")
    cancellation=db('SELECT id FROM cancellation_requests WHERE order_id=101')
    reject('inquiry cancellation must belong to order',f"INSERT INTO support_inquiries(requester_id,order_id,cancellation_request_id,type,status,subject,created_at,updated_at) VALUES(2,102,{cancellation},'CANCELLATION_REVIEW','OPEN','review','2026-11-01','2026-11-01')")
    db(f"INSERT INTO support_inquiries(requester_id,order_id,cancellation_request_id,type,status,subject,created_at,updated_at) VALUES(2,101,{cancellation},'CANCELLATION_REVIEW','OPEN','review','2026-11-01','2026-11-01')")
    inquiry=db('SELECT id FROM support_inquiries WHERE order_id=101')
    message=f"INSERT INTO support_messages(inquiry_id,sender_id,client_message_id,content,created_at) VALUES({inquiry},2,'00000000-0000-0000-0000-000000000777','help','2026-11-01')"
    db(message);reject('support message deduplication',message)
    print('Mode: '+('runtime V1-V6' if runtime else 'review candidate'))
    print('MySQL: '+db('SELECT VERSION()'))
    print('PASS '+str(len(passed))+' schema checks:')
    for item in passed:
        print(' - '+item)
    print('Scope: DDL, backfill, constraints and DB rollback; not application authorization or concurrent API tests.')
finally:
    # Fixed, unique name owned by this invocation. Never remove user databases/containers.
    docker('rm','-f','-v',NAME)
