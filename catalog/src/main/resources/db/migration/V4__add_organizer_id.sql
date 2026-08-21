ALTER TABLE events ADD COLUMN organizer_id UUID;
CREATE INDEX idx_events_organizer_id ON events(organizer_id);
