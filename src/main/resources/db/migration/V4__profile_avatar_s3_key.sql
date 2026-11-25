-- V4: avatars move from the container's local disk to a private S3 bucket.
--
-- image_key holds the S3 object key of an uploaded avatar; responses turn it into
-- a presigned URL at read time. image_url stays for externally hosted images
-- supplied as a plain URL.

ALTER TABLE profiles ADD COLUMN image_key VARCHAR(512);

-- Local-disk avatars were served from /uploads/**. Render's filesystem is
-- ephemeral, so those files are already gone; clear the dead links.
UPDATE profiles SET image_url = NULL WHERE image_url LIKE '%/uploads/%';
