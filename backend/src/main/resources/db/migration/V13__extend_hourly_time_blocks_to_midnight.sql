-- Preserve all existing codes and references. New morning codes are sorted by
-- start_minute by the server rather than renumbering the original B01~B10.
ALTER TABLE time_blocks DROP CONSTRAINT time_blocks_check1;
ALTER TABLE time_blocks ADD CONSTRAINT time_blocks_canonical_mapping CHECK (
    (code, start_minute, end_minute, duration_minutes) IN (
        ('B01',720,780,60), ('B02',780,840,60), ('B03',840,900,60),
        ('B04',900,960,60), ('B05',960,1020,60), ('B06',1020,1080,60),
        ('B07',1080,1170,90), ('B08',1170,1260,90), ('B09',1260,1350,90), ('B10',1350,1440,90),
        ('B11',0,60,60), ('B12',60,120,60), ('B13',120,180,60),
        ('B14',180,240,60), ('B15',240,300,60), ('B16',300,360,60),
        ('B17',360,420,60), ('B18',420,480,60), ('B19',480,540,60),
        ('B20',540,600,60), ('B21',600,660,60), ('B22',660,720,60)));

INSERT INTO time_blocks (code, start_minute, end_minute, duration_minutes) VALUES
    ('B11',0,60,60), ('B12',60,120,60), ('B13',120,180,60),
    ('B14',180,240,60), ('B15',240,300,60), ('B16',300,360,60),
    ('B17',360,420,60), ('B18',420,480,60), ('B19',480,540,60),
    ('B20',540,600,60), ('B21',600,660,60), ('B22',660,720,60);
