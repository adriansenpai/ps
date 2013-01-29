#!/bin/bash

# Copyright (c) 2012, Psiphon Inc.
# All rights reserved.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

MAILDECRYPTOR_USER="maildecryptor"

if [ ! -f ./maildecryptor.conf ]; then
  echo "This script must be run from the source directory."
  exit 1
fi

echo "You must already have created the user $MAILDECRYPTOR_USER, otherwise this script will fail. See the README for details."
echo ""

sed "s|fill-in-with-path-to-source|\"`pwd`\"|" maildecryptor.conf > maildecryptor.conf.configured
sed "s|fill-in-with-path-to-source|\"`pwd`\"|" s3decryptor.conf > s3decryptor.conf.configured
sed "s|fill-in-with-path-to-source|\"`pwd`\"|" mailsender.conf > mailsender.conf.configured
sed "s|fill-in-with-path-to-source|\"`pwd`\"|" statschecker.conf > statschecker.conf.configured

sudo cp maildecryptor.conf.configured /etc/init/maildecryptor.conf
sudo cp s3decryptor.conf.configured /etc/init/s3decryptor.conf
sudo cp mailsender.conf.configured /etc/init/mailsender.conf
sudo cp statschecker.conf.configured /etc/init/statschecker.conf

sudo chmod 0400 *.pem conf.json
sudo chown $MAILDECRYPTOR_USER:$MAILDECRYPTOR_USER *.pem conf.json

sudo stop maildecryptor
sudo stop s3decryptor
sudo stop mailsender
sudo stop statschecker

echo "Done."
echo ""
echo "To start the feedback processing daemons execute:"
echo " > sudo start maildecryptor"
echo " > sudo start s3decryptor"
echo " > sudo start mailsender"
echo " > sudo start statschecker"
echo ""