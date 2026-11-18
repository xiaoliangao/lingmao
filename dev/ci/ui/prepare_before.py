"""Minimal baseline fixtures use only APIs present before either audit fix."""
import os
from datetime import timedelta
from pathlib import Path

from fixtures.domain_seed import DomainFixture
from harness import ApiClient, business_day, local_url, read_fixture, write_evidence

f = read_fixture(os.environ['SCM_UI_FIXTURE_INPUT'])
api = ApiClient(local_url('SCM_UI_API_BASE', required_port=18080))
api.login()
today = business_day()
f['orderTemplate'] = {
    'companyId': f['companyId'], 'orderType': 'STOCK', 'customerId': None,
    'customerOrderNo': None, 'contractNo': 'AUDIT-BEFORE', 'brand': f['brand'],
    'orderDate': today.isoformat(), 'requiredDate': (today + timedelta(days=60)).isoformat(),
    'salesId': f['userId'], 'merchId': f['userId'], 'currency': 'CNY', 'deliveryReq': None,
    'remark': '匿名基准截图', 'files': [],
    'items': [
        {'styleSkuId': f['styleSkuIdsSameSizeDifferentColor'][0], 'qty': 3, 'price': 12.3456,
         'lineDueDate': (today + timedelta(days=30)).isoformat(), 'remark': '合成色码甲'},
        {'styleSkuId': f['styleSkuIdsSameSizeDifferentColor'][1], 'qty': 5, 'price': 23.4567,
         'lineDueDate': None, 'remark': None},
    ],
}
domain = DomainFixture(api, f)
f['packId'] = domain.create_pack('DEV', with_cost=True)
f['countId'] = domain.new_count(f['warehouseId'])['id']
write_evidence(Path(os.environ['SCM_UI_FIXTURE']), f, api.token)
