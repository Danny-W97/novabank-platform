# Provision this policy/token outside the panel with an existing Vault administrator.
# Never grant it to bank-prod/shop-prod. No root, KV writes, seal, rekey or init.
path "auth/token/lookup-self" { capabilities = ["read"] }
path "sys/auth" { capabilities = ["read"] }
path "sys/auth/approle" { capabilities = ["create", "update", "sudo"] }
path "sys/policies/acl/bank-prod" { capabilities = ["create", "update"] }
path "sys/policies/acl/shop-prod" { capabilities = ["create", "update"] }
path "auth/approle/role/bank-prod" { capabilities = ["create", "update"] }
path "auth/approle/role/shop-prod" { capabilities = ["create", "update"] }
path "auth/approle/role/bank-prod/role-id" { capabilities = ["read"] }
path "auth/approle/role/shop-prod/role-id" { capabilities = ["read"] }
path "auth/approle/role/bank-prod/secret-id" { capabilities = ["create", "update"] }
path "auth/approle/role/shop-prod/secret-id" { capabilities = ["create", "update"] }
path "auth/approle/role/bank-prod/secret-id/lookup" { capabilities = ["update"] }
path "auth/approle/role/shop-prod/secret-id/lookup" { capabilities = ["update"] }
path "auth/approle/role/bank-prod/secret-id-accessor/lookup" { capabilities = ["update"] }
path "auth/approle/role/shop-prod/secret-id-accessor/lookup" { capabilities = ["update"] }
path "auth/approle/role/bank-prod/secret-id-accessor/destroy" { capabilities = ["update"] }
path "auth/approle/role/shop-prod/secret-id-accessor/destroy" { capabilities = ["update"] }
