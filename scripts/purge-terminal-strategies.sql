SET search_path TO quantdcx;

-- Hard-delete REJECTED / ARCHIVED and dependents
DELETE FROM orders
WHERE bot_id IN (
  SELECT b.id FROM bots b
  JOIN strategies s ON s.id = b.strategy_id
  WHERE s.status IN ('REJECTED', 'ARCHIVED')
);

DELETE FROM positions
WHERE bot_id IN (
  SELECT b.id FROM bots b
  JOIN strategies s ON s.id = b.strategy_id
  WHERE s.status IN ('REJECTED', 'ARCHIVED')
);

DELETE FROM bots
WHERE strategy_id IN (
  SELECT id FROM strategies WHERE status IN ('REJECTED', 'ARCHIVED')
);

DELETE FROM signals
WHERE strategy_id IN (
  SELECT id FROM strategies WHERE status IN ('REJECTED', 'ARCHIVED')
);

DELETE FROM backtests
WHERE strategy_id IN (
  SELECT id FROM strategies WHERE status IN ('REJECTED', 'ARCHIVED')
);

DELETE FROM strategies WHERE status IN ('REJECTED', 'ARCHIVED');

SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;
