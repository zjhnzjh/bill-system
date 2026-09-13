USE bill_order;
START TRANSACTION;

DELETE FROM recovery_jobs;
DELETE FROM trace_events;
DELETE FROM kafka_deliveries;
DELETE FROM outbox_events;
DELETE FROM order_requests;
DELETE FROM bill_inventory.inventory_audit;
DELETE FROM bill_payment.payment_audit;
DELETE FROM bill_payment.payments;
DELETE FROM bill_inventory.inventory_reservations;
DELETE FROM orders;

UPDATE bill_inventory.inventory_items SET available = 100, reserved = 0 WHERE sku = 'LIFE-DEMO-001';
UPDATE bill_inventory.inventory_items SET available = 1, reserved = 0 WHERE sku = 'LIFE-CONCURRENCY-001';
UPDATE bill_inventory.inventory_items SET available = 100000, reserved = 0 WHERE sku = 'LIFE-LOAD-001';

COMMIT;
