USE bill_order;
START TRANSACTION;

DELETE p
FROM bill_payment.payments p
JOIN bill_order.orders o ON o.id = p.order_id
WHERE o.sku = 'LIFE-LOAD-001';

DELETE r
FROM bill_inventory.inventory_reservations r
JOIN bill_order.orders o ON o.id = r.order_id
WHERE o.sku = 'LIFE-LOAD-001';

DELETE FROM bill_order.trace_events
WHERE trace_id LIKE 'load-trace-%';

DELETE FROM bill_order.orders
WHERE sku = 'LIFE-LOAD-001';

UPDATE bill_inventory.inventory_items
SET available = 100000, reserved = 0
WHERE sku = 'LIFE-LOAD-001';

COMMIT;
